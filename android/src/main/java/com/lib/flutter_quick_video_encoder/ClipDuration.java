package com.lib.flutter_quick_video_encoder;

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

    /**
     * The picture track's nominal frame rate, or null if the container is silent.
     *
     * <p><b>Nominal, and the word is load-bearing.</b> Action-camera footage is
     * routinely variable-rate — a GoPro dropping frames in low light does not
     * announce it — so there may be no single number that is true of the whole
     * clip. {@code KEY_FRAME_RATE} is what the container *claims*, which is the
     * right answer for the thing this is for: choosing how much footage a strip
     * of a given width should span, where being a frame or two out changes
     * nothing a rider can see. It would be the wrong answer for anything that
     * had to land on an exact frame.
     */
    public void frameRateOf(final String path, final Delivery delivery) {
        ask(path, delivery, "frame rate", ClipDuration::readFrameRate);
    }

    /** One worker hop, one refusal path, for both questions. */
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
     * The first video track's frame rate, or null.
     *
     * <b>First rather than longest.</b> {@link #read} takes the maximum over
     * video tracks because a trim handle must be bounded by the longest thing it
     * could show. A rate has no maximum worth taking: two video tracks at
     * different rates is not a file this app has a policy for, and averaging
     * them would invent a number true of neither.
     *
     * <b>Read as a float and as an integer.</b> {@code KEY_FRAME_RATE} is
     * documented as an integer on a format handed *to* a codec and comes back as
     * a float from some extractors and an integer from others — asking for the
     * wrong one throws {@code ClassCastException}, which is how this returns
     * null for a file it could read perfectly well.
     */
    private static Double readFrameRate(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(path);
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    continue;
                }
                double fps;
                try {
                    fps = format.getFloat(MediaFormat.KEY_FRAME_RATE);
                } catch (ClassCastException notAFloat) {
                    fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                }
                // A zero or a nonsense rate is the container saying nothing, and
                // saying nothing is what null is for — a caller dividing a strip
                // width by this must not get an infinity.
                if (fps > 0 && fps < 1000) {
                    return fps;
                }
            }
            return null;
        } catch (Throwable error) {
            Log.w(TAG, "could not read frame rate: " + path, error);
            return null;
        } finally {
            extractor.release();
        }
    }
}
