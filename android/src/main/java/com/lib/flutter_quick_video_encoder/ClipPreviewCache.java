package com.lib.flutter_quick_video_encoder;

import android.util.Log;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Decoders held open so a trim handle can be dragged.
 *
 * <p>The export's {@link ClipCompositor} keeps a cache with the same shape and
 * for the same reason, and this is deliberately not that one. The two have
 * opposite access patterns — a film walks each clip forward exactly once, a
 * rider scrubs one clip back and forth — so sharing a cache would mean a scrub
 * leaves the export's reader parked at an instant the film has already gone
 * past, and the next frame of the export pays for a seek that nothing in the
 * film asked for. Separate caches, and {@code setup} drops this one, so the two
 * can never be holding the same decoder.
 *
 * <p><b>Every reader is touched from one background thread and only from
 * there.</b> A cold open of a 4K HEVC clip is a couple of hundred milliseconds,
 * and the platform thread is the thread Flutter draws the handle on — decoding
 * there is how a preview makes the gesture it is previewing stutter. One thread
 * rather than a pool, because it also serializes access to the readers for free:
 * there are no locks in here and there is nothing to get wrong.
 */
final class ClipPreviewCache {

    private static final String TAG = "[FQVE-Android]";

    /**
     * How many decoders may be open at once.
     *
     * <p>Two rather than the compositor's three. A rider scrubs one clip at a
     * time and the second slot is there so that flicking between two clips does
     * not reopen either — while a phone's supply of concurrent 4K decoders is
     * small and device-specific, and every one a preview holds is one an export
     * may be about to want.
     */
    private static final int MAX_OPEN_READERS = 2;

    /**
     * How long a reader may sit unused before it gives its decoder back.
     *
     * <p>Counted in wall clock, unlike the compositor's frame count, because
     * there are no frames here — there is a hand on a handle, and the thing that
     * matters is how long ago it stopped moving. Long enough to survive reading
     * the screen and reaching for the handle again, short enough that a rider who
     * wandered off is not holding hardware.
     *
     * <p>It is a backstop rather than the mechanism. `releaseClipPreviews` is
     * what the screen is supposed to call; this is what covers the screen that
     * forgot.
     */
    private static final long IDLE_MILLIS = 20_000L;

    /** What a decode call answers with. Delivered on the worker thread. */
    interface Delivery {
        /** The frame, or null when the clip could not be opened or decoded. */
        void onImage(ClipPreview.Image image);
    }

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "fqve-clip-preview");
                // Daemon, so a host process that is shutting down is not held up
                // by a thread whose only remaining job is to close decoders it is
                // about to lose anyway.
                thread.setDaemon(true);
                return thread;
            });

    private final LinkedHashMap<String, Entry> readers = new LinkedHashMap<>();

    private static final class Entry {
        final ClipReader reader;
        long lastUsedMillis;

        Entry(ClipReader reader, long nowMillis) {
            this.reader = reader;
            this.lastUsedMillis = nowMillis;
        }
    }

    /**
     * The frame [path] shows at [timeUs], capped at [maxEdge] on its longer side.
     *
     * <p>Answers with null rather than throwing for a clip this device cannot
     * read, which is the same answer `checkClipsDecodable` gives and for the same
     * reason: the caller's next move is to show the rider that this clip cannot
     * be previewed, not to fail.
     */
    void frameAt(String path, long timeUs, int maxEdge, Delivery delivery) {
        worker.execute(() -> {
            ClipPreview.Image image = null;
            try {
                image = ClipPreview.from(readerFor(path).frameAtTime(timeUs), maxEdge);
            } catch (Exception e) {
                // The reader is dropped rather than kept, because a decode that
                // threw leaves it at an instant nobody can name — reusing it
                // would answer the next scrub with a frame from wherever it
                // stopped, which looks like a picture rather than like an error.
                forget(path);
                final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w(TAG, "CLIP preview failed for " + path + " at " + timeUs + "us: " + reason);
            }
            delivery.onImage(image);
            sweepLater();
        });
    }

    /**
     * Closes every reader, and does not return until they are closed.
     *
     * <p>Blocking is the point. The two callers are the screen going away and an
     * export starting, and an export that begins while a decoder is still being
     * handed back is the failure this is here to prevent — one that would surface
     * on a phone, once, as an unrelated clip refusing to open.
     */
    void release() {
        try {
            worker.submit(this::closeAll).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // A decode that has wedged must not wedge the export behind it. The
            // readers stay in the map and the next sweep will try again; saying
            // so is better than either hanging or pretending it worked.
            Log.w(TAG, "CLIP preview release did not complete: " + e);
        }
    }

    /** Lets the worker thread go, for good. */
    void shutdown() {
        worker.execute(this::closeAll);
        worker.shutdown();
    }

    // ---- worker thread only -------------------------------------------------

    private ClipReader readerFor(String path) throws java.io.IOException {
        final long now = System.currentTimeMillis();
        final Entry existing = readers.get(path);
        if (existing != null) {
            existing.lastUsedMillis = now;
            return existing.reader;
        }
        while (readers.size() >= MAX_OPEN_READERS) {
            evictOldest();
        }
        final ClipReader opened = new ClipReader(path);
        readers.put(path, new Entry(opened, now));
        return opened;
    }

    private void forget(String path) {
        final Entry entry = readers.remove(path);
        if (entry != null) {
            entry.reader.close();
        }
    }

    private void closeAll() {
        for (final Entry entry : readers.values()) {
            entry.reader.close();
        }
        readers.clear();
    }

    /**
     * Asks the worker to look again once the idle window has passed.
     *
     * <p>Scheduled rather than checked on the next call, because there may not be
     * a next call: a rider who stops scrubbing and puts the phone down is exactly
     * the case where a decoder would otherwise be held indefinitely, and it is
     * also the case nobody notices until some other clip will not open.
     */
    private void sweepLater() {
        worker.schedule(this::sweep, IDLE_MILLIS + 500L, TimeUnit.MILLISECONDS);
    }

    private void sweep() {
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<String, Entry>> it = readers.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Entry> e = it.next();
            if (now - e.getValue().lastUsedMillis >= IDLE_MILLIS) {
                Log.i(TAG, "CLIP preview evict idle " + e.getKey());
                e.getValue().reader.close();
                it.remove();
            }
        }
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldestMillis = Long.MAX_VALUE;
        for (final Map.Entry<String, Entry> e : readers.entrySet()) {
            if (e.getValue().lastUsedMillis < oldestMillis) {
                oldestMillis = e.getValue().lastUsedMillis;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey == null) {
            return;
        }
        Log.i(TAG, "CLIP preview evict oldest " + oldestKey + " to stay under "
                + MAX_OPEN_READERS + " decoders");
        readers.remove(oldestKey).reader.close();
    }
}
