package com.lib.flutter_quick_video_encoder;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

class InputData {
    enum DataType { VIDEO, AUDIO, STOP }
    public DataType type;
    public byte[] data;

    public InputData(DataType type, byte[] data) {
        this.type = type;
        this.data = data;
    }
}

class EncodedData {
    public ByteBuffer byteBuffer;
    public MediaCodec.BufferInfo bufferInfo;

    public EncodedData(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        this.byteBuffer = byteBuffer;
        this.bufferInfo = bufferInfo;
    }
}

public class FlutterQuickVideoEncoderPlugin implements
    FlutterPlugin,
    MethodChannel.MethodCallHandler
{
    private static final String TAG = "[FQVE-Android]";
    private static final String CHANNEL_NAME = "flutter_quick_video_encoder/methods";
    private static final String THERMAL_CHANNEL_NAME = "flutter_quick_video_encoder/thermal";

    private MethodChannel mMethodChannel;
    private EventChannel mThermalChannel;
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int mWidth;
    private int mHeight;
    private int mFps;
    private boolean mMuxerStarted;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaMuxer mMediaMuxer;
    private int mVideoFrameIdx;
    private int mAudioFrameIdx;
    private int mVideoTrackIndex;
    private int mAudioTrackIndex;
    private int mTrackCount;
    private int mAudioChannels;
    private Queue<EncodedData> videoQueue = new LinkedList<>();
    private Queue<EncodedData> audioQueue = new LinkedList<>();

    /**
     * Fills the rectangles the painter cleared, or null until a frame asks for
     * one. Held across frames because every clip is one decode session, and
     * released with the film it was opened for — keeping it would leak a
     * decoder per source per export, and a second export of the same file would
     * resume the first one's reader partway through rather than starting again.
     */
    private ClipCompositor mClipCompositor;

    /**
     * Decoders held open for a trim handle, or null until one is asked for.
     *
     * <p>Separate from {@link #mClipCompositor} on purpose — the two read the
     * same files in opposite patterns — and released whenever an export sets up,
     * so a screen that forgot to let go cannot starve the film of a decoder.
     */
    private ClipPreviewCache mClipPreviews;
    private StillDecoder mStillDecoder;
    private ClipDuration mClipDurations;
    private ClipCheck mClipChecks;

    /**
     * Says when the phone starts throttling, so a slower render has a reason
     * attached to it rather than being a number nobody can account for.
     */
    private ThermalWatch mThermal;

    // input queue for video, audio, and stop signals
    private BlockingQueue<InputData> inputQueue = new LinkedBlockingQueue<>(5);

    // signal encoding success or error
    private CompletableFuture<Void> processingResult;

    private Thread processingThread;

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        BinaryMessenger messenger = binding.getBinaryMessenger();
        mMethodChannel = new MethodChannel(messenger, CHANNEL_NAME);
        mMethodChannel.setMethodCallHandler(this);
        mThermal = ThermalWatch.of(binding.getApplicationContext());

        // **Pushed, not polled, and one source for two consumers.** The perf row
        // wants the transitions to build a trace; the Export screen wants the
        // current rung to draw a gauge. Asking twice invites the two to
        // disagree, and polling per frame would be a channel round trip to
        // answer a question that changes three times in an export.
        mThermalChannel = new EventChannel(messenger, THERMAL_CHANNEL_NAME);
        mThermalChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                if (mThermal == null) {
                    // A device that cannot answer says so once rather than
                    // going quiet, which reads the same as "never throttled".
                    events.error("ThermalUnavailable",
                            "this device does not report thermal status", null);
                    return;
                }
                mThermal.listen((level, name, throttling, headroom) -> {
                    Map<String, Object> event = new java.util.LinkedHashMap<>();
                    event.put("level", level);
                    event.put("name", name);
                    event.put("throttling", throttling);
                    if (!Float.isNaN(headroom)) {
                        event.put("headroom", (double) headroom);
                    }
                    // The sink is not thread-safe and the listener fires on the
                    // platform's own thread, so hop deliberately rather than
                    // hoping they coincide.
                    mMainHandler.post(() -> events.success(event));
                });
            }

            @Override
            public void onCancel(Object arguments) {
                if (mThermal != null) {
                    mThermal.stopListening();
                }
            }
        });
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        mMethodChannel.setMethodCallHandler(null);
        releaseClipCompositor();
        if (mClipPreviews != null) {
            mClipPreviews.shutdown();
            mClipPreviews = null;
        }
        if (mThermal != null) {
            mThermal.close();
            mThermal = null;
        }
        // **Here rather than in releaseClipPreviews, which is where these
        // started.** Neither of these has anything to do with clip previews:
        // that method is called every time the Clips screen stops being shown
        // and at the start of every export, so releasing the still decoder
        // there tore down a thread the shot list was about to use again — and
        // detach, the one moment they genuinely must go, released neither, so
        // an activity recreate stranded a thread of each per cycle.
        if (mStillDecoder != null) {
            mStillDecoder.release();
            mStillDecoder = null;
        }
        if (mClipChecks != null) {
            mClipChecks.release();
            mClipChecks = null;
        }
        if (mClipDurations != null) {
            mClipDurations.release();
            mClipDurations = null;
        }
    }

    private void releaseClipCompositor() {
        if (mClipCompositor != null) {
            mClipCompositor.release();
            mClipCompositor = null;
        }
    }

    /**
     * Hands back every preview decoder, keeping the worker thread.
     *
     * <p>The cache itself is kept rather than dropped: the thread is the
     * expensive part of it and the readers are what hold hardware, so a screen
     * that scrubs, exports and scrubs again pays for one thread rather than two.
     */
    private void releaseClipPreviews() {
        if (mClipPreviews != null) {
            mClipPreviews.release();
        }
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        try{
            switch (call.method) {
                case "setLogLevel":
                {
                    result.success(null);
                    break;
                }
                case "setup":
                {
                    // Clear queues
                    inputQueue.clear();
                    videoQueue.clear();
                    audioQueue.clear();

                    // reset
                    stopProcessingThread();
                    releaseClipCompositor();
                    // And the preview readers with them. A phone has a small,
                    // device-specific supply of concurrent 4K decoders, so a
                    // Clips screen that is still holding one is a clip this
                    // export will not be able to open — and that surfaces as an
                    // unrelated file failing, which is the wrong diagnosis every
                    // time. Cheap: the screen is not scrubbing during an export,
                    // so nothing reopens.
                    releaseClipPreviews();

                    // Extract parameters
                    int width =         call.argument("width");
                    int height =        call.argument("height");
                    int fps =           call.argument("fps");
                    int videoBitrate =  call.argument("videoBitrate");
                    int audioChannels = call.argument("audioChannels");
                    int audioBitrate =  call.argument("audioBitrate");
                    int sampleRate =    call.argument("sampleRate");
                    String filepath =   call.argument("filepath");

                    // save
                    mFps = fps;
                    mHeight = height;
                    mWidth = width;
                    mAudioChannels = audioChannels;

                    // reset
                    mVideoFrameIdx = 0;
                    mAudioFrameIdx = 0;
                    mMuxerStarted = false;
                    mTrackCount = 0;

                    // Initialize the MediaMuxer
                    Log.i(TAG, "calling new MediaMuxer()");
                    mMediaMuxer = new MediaMuxer(filepath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                    // setup video?
                    if (width != 0 && height != 0) {

                        // color format
                        int colorFormat = getColorFormat();
                        if (isColorFormatSupported(EncoderFormat.MIME, colorFormat) == false) {
                            result.error("UnsupportedColorFormat", "COLOR_FormatYUV420Flexible is not supported", null);
                            return;
                        }
                            
                        // Video format. Built in `EncoderFormat` rather than
                        // here so a test can assert the color tags are on it —
                        // they are the part a later change drops silently while
                        // the pixels still look approximately right.
                        Log.i(TAG, "calling MediaFormat.createVideoFormat()");
                        MediaFormat videoFormat =
                                EncoderFormat.video(width, height, videoBitrate, fps, colorFormat);
                        //videoFormat.setInteger(MediaFormat.KEY_LATENCY, 1);

                        
                        // Video encoder
                        mVideoEncoder = MediaCodec.createEncoderByType(EncoderFormat.MIME);
                        Log.i(TAG, "calling mVideoEncoder.configure()");
                        mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                        // start
                        try {
                            Log.i(TAG, "calling mVideoEncoder.start()");
                            mVideoEncoder.start();
                        } catch (Exception e) {
                            result.error("Hardware", "Could not start video encoder. Check logs.", null);
                            return;
                        }
                    }

                    // setup audio?
                    if (audioChannels != 0 && sampleRate != 0) {

                        // check audio support
                        int audioProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
                        if (!isAudioFormatSupported(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, audioProfile)) {
                            result.error("UnsupportedAudioFormat", "AAC audio is not supported", null);
                            return;
                        }

                        // Audio format
                        Log.i(TAG, "calling MediaFormat.createAudioFormat()");
                        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, audioChannels);
                        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
                        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, audioProfile);

                        // Audio encoder
                        mAudioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                        Log.i(TAG, "calling mAudioEncoder.configure()");
                        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                        // start
                        try {
                            Log.i(TAG, "calling mAudioEncoder.start()");
                            mAudioEncoder.start();
                        } catch (Exception e) {
                            result.error("Hardware", "Could not start audio encoder. Check logs.", null);
                            return;
                        }
                    }

                    // Start thread
                    startProcessingThread();

                    // success
                    result.success(null);

                    break;
                }
                case "appendVideoFrame":
                {
                    // if processing error, throw exception
                    if (processingResult.isDone()) {
                        processingResult.get();
                    }

                    byte[] rawRgba = call.argument("rawRgba");

                    // Sampled per frame, reported only when it changes or once
                    // every ten seconds. The moment throttling starts is the
                    // event that explains a slowdown, and a purely periodic
                    // sample would put it up to its own interval away from where
                    // it happened.
                    if (mThermal != null) {
                        mThermal.sample(mVideoFrameIdx);
                    }

                    // Fill the caller's holes with video before anything is
                    // encoded.
                    //
                    // In place, and that is safe here in a way it is not on
                    // Apple: `rawRgba` is decoded out of the method channel
                    // into a byte[] of its own, so there is no caller's buffer
                    // to protect. The Apple side takes a mutable copy for
                    // exactly that reason.
                    //
                    // Before the YUV conversion, necessarily — the compositor
                    // reads the straight alpha the painter wrote, and the
                    // conversion below throws alpha away.
                    List<?> holes = call.argument("holes");
                    if (holes != null && !holes.isEmpty()) {
                        if (mClipCompositor == null) {
                            mClipCompositor = new ClipCompositor();
                        }
                        try {
                            mClipCompositor.fill(rawRgba, mWidth, mHeight, holes);
                        } catch (Exception e) {
                            // Loud, and it ends the export. A hole that is not
                            // filled encodes as a black window in a file that
                            // is otherwise valid and the right length, which
                            // nothing downstream can tell from a film that came
                            // out right.
                            Log.e(TAG, "clip composite failed", e);
                            result.error("ClipCompositeFailed", e.getMessage(),
                                    stackTraceOf(e));
                            return;
                        }
                    }

                    // Convert RGBA to YUV420
                    // Perf: we get better results doing this here on the Platform thread,
                    // as opposed to doing it in the processing thread.
                    byte[] yuv420 = rgbaToYuv420Planar(rawRgba, mWidth, mHeight);

                    // Create InputData
                    InputData inputData = new InputData(InputData.DataType.VIDEO, yuv420);

                    // Put InputData into inputQueue (blocks if full)
                    inputQueue.put(inputData);

                    // Return immediately
                    result.success(null);
                    break;
                }
                case "appendAudioFrame":
                {
                    // if processing error, throw exception
                    if (processingResult.isDone()) {
                        processingResult.get();
                    }

                    byte[] rawPcmArray = call.argument("rawPcm");

                    // Create InputData
                    InputData inputData = new InputData(InputData.DataType.AUDIO, rawPcmArray);

                    // Put InputData into inputQueue (blocks if full)
                    inputQueue.put(inputData);

                    // Return immediately
                    result.success(null);
                    break;
                }
                case "thermalStatus":
                {
                    // For the Dart side, so a rider can be told *why* an export
                    // slowed down and a `PERF` row can say what it was measured
                    // under. A row that cannot name its thermal state invites
                    // two runs to be compared that never asked the same
                    // question — the same reason `host=` and `mode=` are there.
                    //
                    // `worthReporting` is the platform's own judgement rather
                    // than the caller's: LIGHT is normal under sustained load
                    // and saying so would only train people to ignore it.
                    Map<String, Object> thermal = new java.util.LinkedHashMap<>();
                    int status = mThermal == null ? -1 : mThermal.currentStatus();
                    // `level` is the cross-platform one, 0..3, and the only
                    // field two rows from different platforms may be compared
                    // on. `name` and `status` are this platform's own words,
                    // for a log a human reads rather than a column a script
                    // does.
                    thermal.put("level", ThermalWatch.level(status));
                    thermal.put("status", status);
                    thermal.put("name", ThermalWatch.describe(status));
                    thermal.put("throttling", ThermalWatch.worthReporting(status));
                    // The continuous one, for a gauge that has to move rather
                    // than jump. Absent rather than invented where the platform
                    // will not say — a gauge with no reading can be hidden, a
                    // gauge showing a made-up number cannot be unseen.
                    float headroom = mThermal == null ? Float.NaN : mThermal.currentHeadroom();
                    if (!Float.isNaN(headroom)) {
                        thermal.put("headroom", (double) headroom);
                    }
                    result.success(thermal);
                    break;
                }
                case "checkClipsDecodable":
                {
                    // Asked *before* a render rather than discovered inside one.
                    // A refusal that arrives four thousand frames in is a
                    // refusal that arrives after the waiting.
                    //
                    // The answer is a map of path to reason, empty when every
                    // clip opened and produced a frame. Named paths rather than
                    // a count, because the message a rider needs is which of
                    // *their* files this device cannot read.
                    //
                    // **Off the platform thread**, because the work is a codec
                    // configure and a decoded frame per clip and it runs over
                    // the whole import — six clips is already past the ANR
                    // window. See ClipCheck; it froze the import screen on the
                    // exact device the check was protecting.
                    List<?> paths = call.argument("paths");
                    if (paths == null) {
                        result.success(new java.util.LinkedHashMap<String, String>());
                        break;
                    }
                    if (mClipChecks == null) {
                        mClipChecks = new ClipCheck();
                    }
                    mClipChecks.check(paths, failures -> mMainHandler.post(() -> {
                        if (failures == null) {
                            // **Not an empty map.** Empty means every clip
                            // decoded, which is what lets the render start.
                            // Saying that when the check never ran is how a
                            // rider learns about an unreadable clip four
                            // thousand frames in.
                            result.error("checkClipsDecodable",
                                    "the clip check could not be run", null);
                            return;
                        }
                        result.success(failures);
                    }));
                    break;
                }
                case "clipFrameAt":
                {
                    // One decode per scrub position, through the reader the
                    // export uses. A second decoder would be easier and is the
                    // wrong answer: the rider is choosing a trim against this
                    // picture, so it has to be the picture the film will show,
                    // down to the color conversion.
                    String path = call.argument("path");
                    Number atUs = call.argument("atUs");
                    Number maxEdge = call.argument("maxEdge");
                    if (path == null || atUs == null || maxEdge == null) {
                        result.error("clipFrameAt",
                                "path, atUs and maxEdge are all required", null);
                        break;
                    }
                    if (mClipPreviews == null) {
                        mClipPreviews = new ClipPreviewCache();
                    }
                    // Answered from the worker thread, so the platform thread is
                    // free to keep drawing the handle that asked. `result` is not
                    // thread-safe, hence the hop back.
                    // An anonymous class rather than a lambda, because `Delivery`
                    // answers two different things now: a frame that may be
                    // absent, and a call that was retired because the screen let
                    // go. See `ClipPreviewCache.Delivery.onRetired`.
                    mClipPreviews.frameAt(path, atUs.longValue(), maxEdge.intValue(),
                            new ClipPreviewCache.Delivery() {
                                @Override
                                public void onImage(ClipPreview.Image image) {
                                    mMainHandler.post(() -> {
                                        if (image == null) {
                                            result.success(null);
                                            return;
                                        }
                                        Map<String, Object> frame = new java.util.LinkedHashMap<>();
                                        frame.put("rgba", image.rgba);
                                        frame.put("width", image.width);
                                        frame.put("height", image.height);
                                        result.success(frame);
                                    });
                                }

                                @Override
                                public void onRetired() {
                                    // A map with no pixels, rather than null. Dart
                                    // reads the flag and hands back a retired
                                    // outcome, which is a different answer from
                                    // "this clip has no picture" — the whole point
                                    // of the split.
                                    mMainHandler.post(() -> {
                                        Map<String, Object> retired =
                                                new java.util.LinkedHashMap<>();
                                        retired.put("retired", true);
                                        result.success(retired);
                                    });
                                }
                            });
                    break;
                }
                case "clipDuration":
                {
                    // **Asked only when the container would not say.** A
                    // fragmented MP4 states its length as zero in `mvhd` — that
                    // is what the format specifies, not damage — and may omit
                    // the `mehd` that would have carried the real one. The
                    // reader in Dart handles every case it can from the header
                    // alone, precisely so this call stays rare.
                    //
                    // The extractor is the authority rather than a second
                    // opinion: it is what the export will seek inside, so its
                    // answer is the one a trim handle must be bounded by. See
                    // ClipDuration.
                    String durationPath = call.argument("path");
                    if (durationPath == null) {
                        result.error("clipDuration", "path is required", null);
                        break;
                    }
                    if (mClipDurations == null) {
                        mClipDurations = new ClipDuration();
                    }
                    mClipDurations.secondsOf(durationPath,
                            seconds -> mMainHandler.post(() -> result.success(seconds)));
                    break;
                }
                case "clipDetails":
                {
                    // **One probe, every fact.** The frame rate, the codec and
                    // the color tags all come out of one MediaFormat, so asking
                    // separately would parse the header once per question and
                    // let two answers about one file drift apart. See
                    // ClipDuration.detailsOf.
                    String detailsPath = call.argument("path");
                    if (detailsPath == null) {
                        result.error("clipDetails", "path is required", null);
                        break;
                    }
                    if (mClipDurations == null) {
                        mClipDurations = new ClipDuration();
                    }
                    mClipDurations.detailsOf(detailsPath,
                            details -> mMainHandler.post(() -> result.success(details)));
                    break;
                }
                case "stillAt":
                {
                    // **A photograph decoded here rather than by the framework,
                    // because the framework's Android path cannot be told how
                    // big to decode and cannot fail without ending the
                    // process.** See StillDecoder for the two mechanisms; the
                    // short version is that a 200 MP HEIF is an 800 MB
                    // allocation for a 40-point thumbnail, and an
                    // OutOfMemoryError inside it reaches JNI as a pending
                    // exception and aborts.
                    String stillPath = call.argument("path");
                    Number stillEdge = call.argument("maxEdge");
                    if (stillPath == null || stillEdge == null) {
                        result.error("stillAt",
                                "path and maxEdge are both required", null);
                        break;
                    }
                    if (mStillDecoder == null) {
                        mStillDecoder = new StillDecoder();
                    }
                    // Same hop as clipFrameAt, for the same reason: the decode
                    // is off the platform thread and `result` is not
                    // thread-safe.
                    mStillDecoder.decode(stillPath, stillEdge.intValue(),
                            still -> mMainHandler.post(() -> {
                                if (still == null) {
                                    result.success(null);
                                    return;
                                }
                                Map<String, Object> frame = new java.util.LinkedHashMap<>();
                                frame.put("rgba", still.rgba);
                                frame.put("width", still.width);
                                frame.put("height", still.height);
                                // **A field rather than an assumption.** Only
                                // rgba8888 is produced today; naming it is what
                                // lets a wider format arrive later without
                                // every call site having assumed this one.
                                frame.put("format", "rgba8888");
                                frame.put("colorSpace", "srgb");
                                frame.put("hasGainmap", still.hasGainmap);
                                result.success(frame);
                            }));
                    break;
                }
                case "releaseClipPreviews":
                {
                    // **Answered from the worker rather than by parking the
                    // platform thread on it.** This arrives every time the Clips
                    // screen stops being shown, which is routinely *while* its
                    // thumbnails are still decoding — and the worker is serial,
                    // so waiting here waited out the whole backlog. Under merged
                    // platform/UI threading the platform thread is the thread
                    // Dart runs on, so that wait stopped frames outright: the
                    // step indicator sat still for exactly as long as the
                    // thumbnails had left to load, while the already-submitted
                    // frame kept the next step on screen and made it look as
                    // though nothing was wrong.
                    //
                    // Same hop as clipFrameAt and stillAt, for the same reason:
                    // the work is off the platform thread and `result` is not
                    // thread-safe.
                    if (mClipPreviews == null) {
                        result.success(null);
                        break;
                    }
                    mClipPreviews.releaseAsync(
                            () -> mMainHandler.post(() -> result.success(null)));
                    break;
                }
                case "finish":
                {
                    // Let the clip readers go with the film they were opened
                    // for. Each holds a decode session and a frame's worth of
                    // planes, and a 4K source is tens of megabytes of them.
                    releaseClipCompositor();

                    // if processing error, throw exception
                    if (processingResult.isDone()) {
                        processingResult.get();
                    }

                    // Send STOP signal
                    inputQueue.put(new InputData(InputData.DataType.STOP, null));

                    // Wait for processingResult to complete
                    processingResult.get();

                    result.success(null);
                    break;
                }
                case "mux":
                {
                    String videoPath = call.argument("videoPath");
                    String audioPath = call.argument("audioPath");
                    String outputPath = call.argument("outputPath");
                    Integer audioBitrate = call.argument("audioBitrate");
                    muxFile(videoPath, audioPath, outputPath,
                            audioBitrate == null ? 192000 : audioBitrate);
                    result.success(null);
                    break;
                }
                default:
                    result.notImplemented();
                    break;
            }
        } catch (Exception e) {
            result.error("androidException", e.toString(), stackTraceOf(e));
            return;
        }
    }

    private static String stackTraceOf(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Joins a finished video file and a WAV into one MP4.
     *
     * The video is passed through — MediaExtractor hands back the compressed
     * H.264 samples and MediaMuxer writes them straight out, so a long film
     * costs a container rewrite rather than a re-encode. Only the audio is
     * encoded, from linear PCM to AAC.
     *
     * This exists because audio cannot be written alongside video through
     * appendAudioFrame: on Apple two writer inputs deadlock whenever samples
     * are pushed from outside, measured at 37 video frames alternating and 95
     * audio frames audio-first. The mux is the shape that works, and it is
     * also the better one — re-scoring a finished film costs a join rather
     * than another render.
     *
     * NOT YET RUN ON A DEVICE. The Apple side of this is verified: 390 frames
     * in, 390 out, video stream MD5 identical, 311 ms. This is the same design
     * expressed in the platform's own API and nothing more.
     */
    private void muxFile(String videoPath, String audioPath, String outputPath, int audioBitrate)
            throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        MediaCodec encoder = null;
        try {
            videoExtractor.setDataSource(videoPath);
            int videoTrack = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = format;
                    break;
                }
            }
            if (videoTrack < 0) {
                throw new IllegalArgumentException("no video track in " + videoPath);
            }
            videoExtractor.selectTrack(videoTrack);

            WavReader wav = new WavReader(audioPath);

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVideoTrack = muxer.addTrack(videoFormat);

            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // The muxer needs the encoder's *output* format, which only exists
            // once the encoder has produced its first buffer — so the audio
            // track is added mid-flight and start() waits until then.
            int outAudioTrack = -1;
            boolean started = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int index = encoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(index);
                        buffer.clear();
                        // Taken before the read, so it timestamps the start of
                        // this buffer rather than the end of it.
                        long pts = wav.presentationTimeUs();
                        int read = wav.read(buffer);
                        if (read <= 0) {
                            encoder.queueInputBuffer(index, 0, 0, pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            encoder.queueInputBuffer(index, 0, read, pts, 0);
                        }
                    }
                }

                int index = encoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outAudioTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    started = true;
                } else if (index >= 0) {
                    ByteBuffer out = encoder.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && started) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(outAudioTrack, out, info);
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            // Video last, because the muxer cannot accept anything until
            // start(), and start() cannot happen until the encoder has
            // declared its output format above.
            ByteBuffer sample = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
            while (true) {
                int size = videoExtractor.readSampleData(sample, 0);
                if (size < 0) {
                    break;
                }
                videoInfo.offset = 0;
                videoInfo.size = size;
                videoInfo.presentationTimeUs = videoExtractor.getSampleTime();
                videoInfo.flags = (videoExtractor.getSampleFlags()
                        & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(outVideoTrack, sample, videoInfo);
                videoExtractor.advance();
            }

            muxer.stop();
        } finally {
            videoExtractor.release();
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
            }
            if (muxer != null) {
                muxer.release();
            }
        }
    }

    /**
     * Streams the samples out of a 16-bit PCM WAV.
     *
     * Walks the chunk list rather than assuming a 44-byte header: plenty of
     * writers put a LIST or fact chunk before the data, and skipping to a
     * fixed offset would feed metadata to the encoder as if it were audio.
     */
    private static class WavReader {
        final int sampleRate;
        final int channels;
        private final RandomAccessFile file;
        private final long dataStart;
        private final long dataLength;
        private long position;

        WavReader(String path) throws IOException {
            file = new RandomAccessFile(path, "r");
            byte[] header = new byte[12];
            file.readFully(header);
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                throw new IOException("not a RIFF file: " + path);
            }

            int rate = 44100;
            int chans = 2;
            long start = -1;
            long length = 0;
            while (file.getFilePointer() + 8 <= file.length()) {
                byte[] id = new byte[4];
                file.readFully(id);
                long size = readUint32(file);
                String name = new String(id, "US-ASCII");
                if ("fmt ".equals(name)) {
                    readUint16(file);            // audio format
                    chans = readUint16(file);
                    rate = (int) readUint32(file);
                    readUint32(file);            // byte rate
                    readUint16(file);            // block align
                    int bits = readUint16(file);
                    if (bits != 16) {
                        throw new IOException("expected 16-bit PCM, got " + bits);
                    }
                    file.seek(file.getFilePointer() + size - 16);
                } else if ("data".equals(name)) {
                    start = file.getFilePointer();
                    length = size;
                    break;
                } else {
                    file.seek(file.getFilePointer() + size + (size % 2));
                }
            }
            if (start < 0) {
                throw new IOException("no data chunk in " + path);
            }
            sampleRate = rate;
            channels = chans;
            dataStart = start;
            dataLength = length;
            file.seek(dataStart);
        }

        /** Microseconds of audio handed out so far. */
        long presentationTimeUs() {
            return position * 1000000L / (sampleRate * channels * 2L);
        }

        int read(ByteBuffer into) throws IOException {
            long left = dataLength - position;
            if (left <= 0) {
                return 0;
            }
            int want = (int) Math.min(into.remaining(), left);
            byte[] chunk = new byte[want];
            file.readFully(chunk);
            into.put(chunk);
            position += want;
            return want;
        }

        private static long readUint32(RandomAccessFile from) throws IOException {
            byte[] b = new byte[4];
            from.readFully(b);
            return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8)
                    | ((b[2] & 0xFFL) << 16) | ((b[3] & 0xFFL) << 24);
        }

        private static int readUint16(RandomAccessFile from) throws IOException {
            byte[] b = new byte[2];
            from.readFully(b);
            return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
        }
    }

    private void stopProcessingThread() throws InterruptedException {
        if (processingThread != null && processingThread.isAlive()) {
            inputQueue.put(new InputData(InputData.DataType.STOP, null));
            processingThread.join(); 
        }
    }

    private void startProcessingThread() {
        processingResult = new CompletableFuture<>();
        processingThread = new Thread(() -> {
            try {
                while (true) {
                    InputData inputData = inputQueue.take(); // Blocks if queue is empty
                    if (inputData.type == InputData.DataType.STOP) {
                        // Finish processing
                        break;
                    } else if (inputData.type == InputData.DataType.VIDEO) {
                        byte[] yuv420 = inputData.data;
                        feedVideoEncoder(yuv420);
                        drainEncoder(mVideoEncoder, false);
                    } else if (inputData.type == InputData.DataType.AUDIO) {
                        byte[] rawPcmArray = inputData.data;
                        feedAudioEncoder(rawPcmArray);
                        drainEncoder(mAudioEncoder, false);
                    }
                }
                // Finalize encoders
                if (mVideoEncoder != null) {
                    drainEncoder(mVideoEncoder, true);
                    mVideoEncoder.stop();
                    mVideoEncoder.release();
                    mVideoEncoder = null;
                }
                if (mAudioEncoder != null) {
                    drainEncoder(mAudioEncoder, true);
                    mAudioEncoder.stop();
                    mAudioEncoder.release();
                    mAudioEncoder = null;
                }
                if (mMediaMuxer != null) {
                    if (mMuxerStarted) {
                        mMediaMuxer.stop();
                    }
                    mMediaMuxer.release();
                    mMediaMuxer = null;
                }

                // Complete successfully
                processingResult.complete(null);

            } catch (Exception e) {
                Log.e(TAG, "Error in processing thread", e);
                processingResult.completeExceptionally(e);
                inputQueue.clear();  // release input threads
            }
        });
        processingThread.start();
    }

    private void feedVideoEncoder(byte[] yuv420) throws Exception {
        // Calculate presentation time
        long presentationTime = mVideoFrameIdx * 1000000L / mFps;

        // Dequeue input buffer
        int inIdx = mVideoEncoder.dequeueInputBuffer(-1);
        if (inIdx >= 0) {
            // Get buffer size
            ByteBuffer buffer = mVideoEncoder.getInputBuffer(inIdx);
            int size = buffer.capacity();

            // Get input image
            Image image = mVideoEncoder.getInputImage(inIdx);

            // Fill image with YUV data
            fillImage(image, yuv420, mWidth, mHeight);

            // Queue input buffer
            mVideoEncoder.queueInputBuffer(inIdx, 0, size, presentationTime, 0);
        }

        // Increment frame index
        mVideoFrameIdx++;
    }

    private void feedAudioEncoder(byte[] rawPcmArray) throws Exception {
        int offset = 0;
        while (offset < rawPcmArray.length) {
            int inIdx = mAudioEncoder.dequeueInputBuffer(-1);
            if (inIdx >= 0) {
                ByteBuffer buf = mAudioEncoder.getInputBuffer(inIdx);
                buf.clear();

                // Push as many bytes as the encoder allows
                int remaining = buf.remaining();
                int toWrite = Math.min(rawPcmArray.length - offset, remaining);
                buf.put(rawPcmArray, offset, toWrite);

                // Calculate presentation time
                long beginTime = mAudioFrameIdx * 1000000L / mFps;
                long duration = 1000000L / mFps;
                long presentationTime = beginTime + (duration * offset / rawPcmArray.length);

                // queue
                mAudioEncoder.queueInputBuffer(inIdx, 0, toWrite, presentationTime, 0);

                offset += toWrite;
            }
        }

        // Increment frame index
        mAudioFrameIdx++;
    }

    private boolean isColorFormatSupported(String mimeType, int desiredColorFormat) {
        MediaCodecInfo codecInfo = getCodecInfo(mimeType);
        if (codecInfo == null) {
            return false;
        }

        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == desiredColorFormat) {
                return true;
            }
        }

        return false;
    }

    private boolean isAudioFormatSupported(String mimeType, int sampleRate, int profile) {
        MediaCodecInfo codecInfo = getCodecInfo(mimeType);
        if (codecInfo == null) {
            return false;
        }

        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);

        // Check if sample rate is supported
        boolean isSampleRateSupported = false;
        for (int rate : capabilities.getAudioCapabilities().getSupportedSampleRates()) {
            if (rate == sampleRate) {
                isSampleRateSupported = true;
                break;
            }
        }

        // Check if profile is supported
        boolean isProfileSupported = (capabilities.profileLevels != null);
        for (MediaCodecInfo.CodecProfileLevel level : capabilities.profileLevels) {
            if (level.profile == profile) {
                isProfileSupported = true;
                break;
            }
        }

        return isSampleRateSupported && isProfileSupported;
    }

    private MediaCodecInfo getCodecInfo(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private byte[] rgbaToYuv420Planar(byte[] rgba, int width, int height) {
        return FrameYuv.toYuv420Planar(rgba, width, height);
    }

    private void fillImage(Image image, byte[] yuv420, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        logEncoderLayoutOnce(planes);
        FrameYuv.fillPlanes(
                yuv420, width, height,
                planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride());
    }

    /**
     * The layout this encoder actually asked for, printed once per session.
     *
     * <p><b>`ClipReader` has logged this for the decoder since it was written
     * and nothing logged it for the encoder, which is how a fix that covered
     * one plane of three passed for finished.</b>
     * `COLOR_FormatYUV420Flexible` is a family: this device answers
     * {@code y(px=1) u(px=2) v(px=2)} — semiplanar — while a planar one answers
     * 1 across the board, and `FrameYuv.writePlane` takes a completely
     * different path for each. Both are correct and one used to be eight times
     * slower, so the difference was invisible in everything except a stopwatch
     * nobody was holding.
     *
     * <p>Once, not per frame: the answer cannot change inside a session, and
     * 4,339 copies of it would bury the line that matters.
     */
    private void logEncoderLayoutOnce(Image.Plane[] planes) {
        if (mLoggedEncoderLayout) {
            return;
        }
        mLoggedEncoderLayout = true;
        Log.i(TAG, "FEED layout"
                + " y(row=" + planes[0].getRowStride()
                + ",px=" + planes[0].getPixelStride() + ")"
                + " u(row=" + planes[1].getRowStride()
                + ",px=" + planes[1].getPixelStride() + ")"
                + " v(row=" + planes[2].getRowStride()
                + ",px=" + planes[2].getPixelStride() + ")"
                + (planes[1].getPixelStride() == 1 ? " planar" : " semiplanar"));
    }

    private boolean mLoggedEncoderLayout = false;

    private int getColorFormat() {
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    private void signalEndOfStream(MediaCodec encoder) {
        try {
            int inputBufferIndex = encoder.dequeueInputBuffer(-1);
            if (inputBufferIndex >= 0) {
                // No data, but signal end of stream through the buffer flag.
                encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error signaling end of stream: ", e);
            // Handle error
        }
    }

    private int expectedTrackCount() {
        if (mAudioChannels > 0 && mWidth > 0) {
            return 2;
        } else {
            return 1;
        }
    }

    private void processQueues() {
        for (int i = 0; i < 2; i++) {
            Queue<EncodedData> queue = i == 0 ? videoQueue : audioQueue;
            int trackIndex = i == 0 ? mVideoTrackIndex : mAudioTrackIndex;
            while (!queue.isEmpty()) {
                EncodedData data = queue.poll(); // Retrieve and remove the head of the queue
                ByteBuffer byteBuffer = data.byteBuffer;
                MediaCodec.BufferInfo bufferInfo = data.bufferInfo;

                byteBuffer.position(bufferInfo.offset);
                byteBuffer.limit(bufferInfo.offset + bufferInfo.size);
                mMediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo); // Write data to the MediaMuxer
            }
        }
    }

    /**
     * Extracts all pending data from the specified encoder & feed it to the muxer.
     *
     * @param encoder The MediaCodec encoder to drain.
     * @param trackIndex The muxer track index associated with this encoder.
     * @param endOfStream If true, signals end-of-stream to the encoder.
     */
    private void drainEncoder(MediaCodec encoder, boolean endOfStream) {
        final int TIMEOUT_USEC = endOfStream ? 10000 : 0;
        if (endOfStream) {
            signalEndOfStream(encoder);
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER)
            {
                if (!endOfStream) {
                    break; // Exit the loop if not EOS
                }
            } 
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
            {
                MediaFormat newFormat = encoder.getOutputFormat();
                Log.i(TAG, "calling mMediaMuxer.addTrack()");
                if (encoder == mVideoEncoder) {
                    mVideoTrackIndex = mMediaMuxer.addTrack(newFormat);
                } else {
                    mAudioTrackIndex = mMediaMuxer.addTrack(newFormat);
                }
                mTrackCount++;
                if (mTrackCount == expectedTrackCount()) {
                    Log.i(TAG, "calling mMediaMuxer.start()");
                    mMediaMuxer.start();
                    mMuxerStarted = true;
                }
            }
            else if (encoderStatus < 0)
            {
                // Ignore unexpected status.
                Log.e(TAG, "encoderStatus < 0");
            } 
            else 
            {
                ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Ignore codec config data.
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0) {
                    EncodedData data = new EncodedData(encodedData, bufferInfo);
                    if (encoder == mVideoEncoder) {
                        videoQueue.add(data);
                    } else {
                        audioQueue.add(data);
                    }
                    if (mMuxerStarted) {
                        processQueues();
                    }
                }

                encoder.releaseOutputBuffer(encoderStatus, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break; // Break out of the loop if EOS is reached.
                }
            }
        }
    }
}
