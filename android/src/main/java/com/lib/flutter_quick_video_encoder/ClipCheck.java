package com.lib.flutter_quick_video_encoder;

import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Whether this device can decode a rider's clips, asked off the platform thread.
 *
 * <h2>Why this is not a straight call any more</h2>
 *
 * The work is one {@link ClipReader} construction and one decoded frame
 * <em>per clip</em>. Each of those configures a {@code MediaCodec}, waits for the
 * first keyframe and converts a picture — tens to hundreds of milliseconds on a
 * 4K clip off slow storage, and this runs over the whole import. Six clips is
 * already comfortably past Android's five-second ANR window, and it ran on the
 * platform thread.
 *
 * <p>The symptom is not a crash and not a dropped frame: it is the import screen
 * frozen with a spinner that has stopped spinning, then the system offering to
 * close the app. On a rider's phone the check that exists to prevent a bad render
 * was itself the thing that killed the app before the render.
 *
 * <p>Same shape as {@link ClipDuration} and {@link StillDecoder}, and for the
 * same reason — see either for the fuller argument.
 *
 * <h2>A refused check is not a passed check</h2>
 *
 * {@link ClipDuration} answers {@code null} for "no length" and the caller
 * carries on, because a missing duration costs one trim handle its ceiling. This
 * cannot do that. Its empty map means <em>every clip decoded</em>, which is
 * precisely the answer that lets a render start — so returning it when the check
 * never ran would convert "we could not look" into "we looked and it is fine",
 * and the rider would find out four thousand frames later. The delivery below
 * distinguishes the two and the plugin turns the second into a channel error.
 */
public class ClipCheck {
    private static final String TAG = "ClipCheck";

    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fqve-clip-check");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    /**
     * Called on the worker thread.
     *
     * @param failures path to reason, empty when every clip decoded, or
     *                 {@code null} when the check could not be run at all.
     */
    public interface Delivery {
        void onChecked(Map<String, String> failures);
    }

    public void check(final List<?> paths, final Delivery delivery) {
        try {
            mWorker.execute(() -> {
                try {
                    delivery.onChecked(ClipCompositor.undecodable(paths));
                } catch (Throwable error) {
                    // `Throwable` rather than `Exception`, for the reason
                    // StillDecoder documents: the media stack raises Errors too,
                    // and one escaping a worker thread takes the process with it.
                    // `undecodable` already catches per clip, so reaching here
                    // means something outside the loop failed and the answer is
                    // unknown rather than empty.
                    Log.w(TAG, "clip check failed outright", error);
                    delivery.onChecked(null);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // A refused task still has to answer, or the Dart future never
            // completes and the import stops with nothing to show for it.
            Log.w(TAG, "clip check refused, shutting down");
            delivery.onChecked(null);
        }
    }

    /**
     * Stops taking new work and lets what is queued answer.
     *
     * <p>{@code shutdown()} rather than {@code shutdownNow()}: a discarded task
     * never calls its delivery, so the result is never sent and the Dart future
     * never completes.
     */
    public void release() {
        mWorker.shutdown();
    }
}
