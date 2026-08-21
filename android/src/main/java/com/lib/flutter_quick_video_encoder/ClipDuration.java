package com.lib.flutter_quick_video_encoder;

import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * How long a clip runs, according to the thing that will decode it.
 *
 * <h2>Why the container is not asked instead</h2>
 *
 * It is asked first, in Dart, and this is only reached when it would not say.
 * Two file shapes get that far and neither is exotic:
 *
 * <ul>
 *   <li>A <b>fragmented</b> MP4 carries no samples in its {@code moov}, so the
 *       spec has it write {@code mvhd.duration} as zero. The intended length
 *       goes in {@code mvex/mehd}, which the Dart reader now reads — but a
 *       fragmented file is permitted to omit that too, and then nothing totals
 *       the fragments short of walking every {@code trun} in the file.</li>
 *   <li>A layout the box walker cannot follow at all.</li>
 * </ul>
 *
 * <h2>{@link MediaExtractor}, and specifically not {@code MediaMetadataRetriever}</h2>
 *
 * The retriever would be cheaper and answers a slightly different question. The
 * number wanted here is not "how long is this file" in the abstract — it is the
 * ceiling a trim handle may be dragged to, and the range the export will later
 * seek inside. The export seeks with {@code MediaExtractor}, through
 * {@link ClipReader}. So the extractor is the authority by construction: if the
 * two ever disagree, a rider allowed to trim to the retriever's answer would
 * choose a moment the export cannot seek to, and the film would come back short
 * of what was asked for with nothing reporting why.
 *
 * A file this cannot time is, in practice, a file this device cannot decode,
 * which {@code checkClipsDecodable} already reports by name. So there is no
 * second fallback here on purpose: a length invented for a clip that will not
 * open is worse than admitting there is none.
 */
public class ClipDuration {
    private static final String TAG = "ClipDuration";

    /**
     * Its own single thread.
     *
     * {@code setDataSource} parses a header and, for a fragmented file, may walk
     * a good deal more than that — hundreds of milliseconds on a large clip off
     * slow storage. That is not work to do on the platform thread while an
     * import screen is trying to draw its progress bar.
     */
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fqve-clip-duration");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    /** Called on the worker thread with the answer, or null if none. */
    public interface Delivery {
        void onValue(Double value);
    }

    public void secondsOf(final String path, final Delivery delivery) {
        ask(path, delivery, "timing", ClipDuration::read);
    }

    /** Called on the worker thread with everything the container states. */
    public interface Details {
        void onDetails(java.util.Map<String, Object> details);
    }

    /**
     * Everything {@link MediaExtractor} states about the first video track.
     *
     * <p><b>Null values are kept out of the map rather than put in it</b>, so a
     * caller reading a key that is absent gets the same answer as one reading a
     * key that was never asked about: nothing was said. A container is allowed
     * to be silent about its color, its profile or its rate.
     *
     * <p><b>The frame rate here is nominal, and the word is load-bearing.</b>
     * Action-camera footage is routinely variable-rate — a camera dropping
     * frames in low light does not announce it — so there may be no single
     * number true of the whole clip. It is also an integer approximation on this
     * platform: this extractor answers 30 for a 30000/1001 file where Apple's
     * {@code nominalFrameRate} answers 29.970. Right for choosing a scale, wrong
     * for landing on a frame.
     */
    public void detailsOf(final String path, final Details delivery) {
        try {
            mWorker.execute(() -> delivery.onDetails(readDetails(path)));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            Log.w(TAG, "clip details refused, shutting down: " + path);
            delivery.onDetails(null);
        }
    }

    /** One worker hop, one refusal path. */
    private void ask(
            final String path,
            final Delivery delivery,
            final String what,
            final Probe probe) {
        try {
            mWorker.execute(() -> delivery.onValue(probe.of(path)));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // A refused task still has to answer — see StillDecoder.decode. The
            // caller is an import waiting on a MethodChannel result with no
            // timeout, so a dropped task hangs the whole import rather than
            // costing one clip its length.
            Log.w(TAG, "clip " + what + " refused, shutting down: " + path);
            delivery.onValue(null);
        }
    }

    private interface Probe {
        Double of(String path);
    }

    /**
     * Stops taking new work and lets what is queued answer.
     *
     * <p>{@code shutdown()} rather than {@code shutdownNow()}: a discarded task
     * never calls its delivery, so the {@code MethodChannel} result is never
     * sent and the Dart future never completes. An import waiting on one would
     * simply stop, with no error to show for it.
     */
    public void release() {
        mWorker.shutdown();
    }

    /**
     * The longest video track's duration, in seconds, or null if none says.
     *
     * <b>Video tracks only.</b> An audio track routinely outruns the picture by
     * a frame or two — and a clip whose sound is longer than its image is still
     * only as long as its image, because a film shows pictures. Taking the
     * maximum over the wrong tracks would let a trim handle reach past the last
     * frame, which is the failure this whole path exists to prevent.
     */
    private static Double read(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            long longestUs = -1;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                if (!format.containsKey(MediaFormat.KEY_DURATION)) {
                    continue;
                }
                longestUs = Math.max(longestUs, format.getLong(MediaFormat.KEY_DURATION));
            }
            if (longestUs <= 0) {
                return null;
            }
            return longestUs / 1_000_000.0;
        } catch (Throwable error) {
            // `Throwable` for the reason StillDecoder documents at length: the
            // platform's media stack raises errors as well as exceptions, and a
            // boundary that lets one past turns an unreadable file into a dead
            // process.
            Log.w(TAG, "could not time clip: " + path, error);
            return null;
        } finally {
            extractor.release();
        }
    }

    /**
     * Everything the first video track's format states, as a channel-ready map.
     *
     * <b>First rather than longest.</b> {@link #read} takes the maximum over
     * video tracks because a trim handle must be bounded by the longest thing it
     * could show. None of these facts have a maximum worth taking: two video
     * tracks at different rates or color spaces is not a file this app has a
     * policy for, and blending them would invent values true of neither.
     *
     * <b>Absent keys rather than null values.</b> Silence and "not asked" should
     * read the same on the far side, and a null in a channel map is a value that
     * has to be checked for separately at every use.
     */
    private static java.util.Map<String, Object> readDetails(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("codec", codecOf(mime));
                putInt(out, "width", format, MediaFormat.KEY_WIDTH);
                putInt(out, "height", format, MediaFormat.KEY_HEIGHT);
                putInt(out, "bitsPerSecond", format, MediaFormat.KEY_BIT_RATE);
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    int degrees = ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360;
                    out.put("quarterTurns", degrees / 90);
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    long us = format.getLong(MediaFormat.KEY_DURATION);
                    if (us > 0) {
                        out.put("seconds", us / 1_000_000.0);
                    }
                }
                Double fps = frameRateIn(format);
                if (fps != null) {
                    out.put("frameRate", fps);
                }
                String primaries = primariesIn(format);
                if (primaries != null) {
                    out.put("colorPrimaries", primaries);
                }
                String transfer = transferIn(format);
                if (transfer != null) {
                    out.put("colorTransfer", transfer);
                }
                String range = rangeIn(format);
                if (range != null) {
                    out.put("colorRange", range);
                }
                Integer depth = depthIn(mime, format);
                if (depth != null) {
                    out.put("bitDepth", depth);
                }
                return out;
            }
            return null;
        } catch (Throwable error) {
            // `Throwable` for the reason StillDecoder documents at length: the
            // platform's media stack raises errors as well as exceptions, and a
            // boundary that lets one past turns an unreadable file into a dead
            // process.
            Log.w(TAG, "could not read clip details: " + path, error);
            return null;
        } finally {
            extractor.release();
        }
    }

    private static void putInt(
            java.util.Map<String, Object> out, String name, MediaFormat format, String key) {
        if (format.containsKey(key)) {
            int value = format.getInteger(key);
            if (value > 0) {
                out.put(name, value);
            }
        }
    }

    /** `video/avc` to `h264`, `video/hevc` to `hevc` — the subtype, plainly. */
    private static String codecOf(String mime) {
        String subtype = mime.substring("video/".length());
        return subtype.equals("avc") ? "h264" : subtype;
    }

    /**
     * The rate, read as a float and as an integer.
     *
     * <b>{@code KEY_FRAME_RATE} comes back as either.</b> It is documented as an
     * integer on a format handed *to* a codec, and extractors differ on what
     * they store — asking for the wrong one throws {@code ClassCastException},
     * which is how this would return null for a file it can read perfectly well.
     */
    private static Double frameRateIn(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            return null;
        }
        double fps;
        try {
            fps = format.getFloat(MediaFormat.KEY_FRAME_RATE);
        } catch (ClassCastException notAFloat) {
            fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        }
        // Zero or nonsense is the container saying nothing, and saying nothing
        // is what absence is for — a caller dividing a strip width by this must
        // not be handed an infinity.
        return fps > 0 && fps < 1000 ? fps : null;
    }

    private static String primariesIn(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            return null;
        }
        switch (format.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
            case MediaFormat.COLOR_STANDARD_BT709: return "bt709";
            case MediaFormat.COLOR_STANDARD_BT601_PAL:
            case MediaFormat.COLOR_STANDARD_BT601_NTSC: return "bt601";
            case MediaFormat.COLOR_STANDARD_BT2020: return "bt2020";
            default: return null;
        }
    }

    /**
     * The transfer function, which is the field that says HDR.
     *
     * Not the bit depth and not the primaries: an eight-bit h264 tagged HLG is
     * HDR-signalled footage, and the corpus proxy is exactly that.
     */
    private static String transferIn(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            return null;
        }
        switch (format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
            case MediaFormat.COLOR_TRANSFER_LINEAR: return "linear";
            case MediaFormat.COLOR_TRANSFER_SDR_VIDEO: return "sdr";
            case MediaFormat.COLOR_TRANSFER_ST2084: return "pq";
            case MediaFormat.COLOR_TRANSFER_HLG: return "hlg";
            default: return null;
        }
    }

    /**
     * Bits per sample, inferred from the profile the container states.
     *
     * <b>Inferred, and it can decline to answer.</b> Apple's format description
     * carries the depth outright; Android's extractor does not, and the only
     * reliable thing next to it is the profile — a stream is ten-bit because it
     * is Main 10 or High 10, not because a field says so. {@code KEY_PROFILE}
     * is itself often absent from an extractor's format, and when it is, this
     * says nothing rather than assuming eight.
     *
     * That asymmetry is real and shows up as a blank where Apple shows a
     * number. Which is why a panel drawing these has to be about *what this
     * device's decoder reports* rather than about the file: the blank is
     * truthful, and pretending otherwise would mean inventing a depth.
     */
    private static Integer depthIn(String mime, MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_PROFILE)) {
            return null;
        }
        int profile = format.getInteger(MediaFormat.KEY_PROFILE);
        if (mime.endsWith("hevc")) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10:
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10:
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus:
                    return 10;
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain:
                    return 8;
                default:
                    return null;
            }
        }
        if (mime.endsWith("avc")) {
            switch (profile) {
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10:
                    return 10;
                case MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline:
                case MediaCodecInfo.CodecProfileLevel.AVCProfileMain:
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh:
                case MediaCodecInfo.CodecProfileLevel.AVCProfileExtended:
                    return 8;
                default:
                    return null;
            }
        }
        return null;
    }

    private static String rangeIn(MediaFormat format) {
        if (!format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
            return null;
        }
        switch (format.getInteger(MediaFormat.KEY_COLOR_RANGE)) {
            case MediaFormat.COLOR_RANGE_FULL: return "full";
            case MediaFormat.COLOR_RANGE_LIMITED: return "limited";
            default: return null;
        }
    }
}
