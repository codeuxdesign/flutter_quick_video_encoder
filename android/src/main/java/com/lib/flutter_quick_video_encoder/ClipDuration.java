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

    /** Called on the worker thread with the length in seconds, or null. */
    public interface Delivery {
        void onDuration(Double seconds);
    }

    public void secondsOf(final String path, final Delivery delivery) {
        try {
            mWorker.execute(() -> delivery.onDuration(read(path)));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // A refused task still has to answer — see StillDecoder.decode. The
            // caller is an import waiting on a MethodChannel result with no
            // timeout, so a dropped task hangs the whole import rather than
            // costing one clip its length.
            Log.w(TAG, "clip timing refused, shutting down: " + path);
            delivery.onDuration(null);
        }
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
}
