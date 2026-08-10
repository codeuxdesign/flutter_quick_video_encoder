package com.lib.flutter_quick_video_encoder;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills the rectangles the painter left with the footage that belongs in them.
 *
 * <p>The render path never draws a clip. {@code paintVideoHoles} *clears* a
 * rectangle in the frame and the Dart side hands over a description of what
 * belongs there — a path, an instant in that file, a destination rectangle in
 * output pixels, and how far to turn the source. This is the Android half of
 * that bargain; {@code FlutterQuickVideoEncoderPlugin.m} is the Apple half and
 * the shape is deliberately the same.
 *
 * <p><b>A clip that cannot be read fails the export.</b> The Apple side leaves
 * the rectangle as the painter left it and carries on, on the grounds that a
 * visibly wrong frame is honest — but a cleared rectangle encodes as a black
 * window, which is exactly the failure this whole feature exists to remove, and
 * it is indistinguishable from the bug. So here a source that will not open, or
 * that no decoder on the device supports, throws: a film that refuses is fine, a
 * film that silently drops the footage is not.
 */
final class ClipCompositor {

    private static final String TAG = "[FQVE-Android]";

    /**
     * How many decoders may be open at once.
     *
     * <p>This has no counterpart on Apple, where readers are cheap and the cache
     * simply grows. A phone has a small, device-specific number of concurrent
     * hardware video decoder instances — often one or two at 4K — and a tour
     * with six clips would ask for six. Holes open once per run and close once,
     * so at most a couple are ever live, and a reader that is evicted and wanted
     * again costs one seek.
     */
    private static final int MAX_OPEN_READERS = 3;

    /**
     * How long a reader may sit unused before it gives its decoder back,
     * counted in frames that carried *some* hole rather than in output frames —
     * this class does not see the ones that carried none. A film whose clips are
     * far apart therefore holds a reader across the gap, which is why the hard
     * cap above exists as well.
     */
    private static final int IDLE_FRAMES = 120;

    private final LinkedHashMap<String, Entry> readers = new LinkedHashMap<>();
    private long frameIndex;

    private static final class Entry {
        final ClipReader reader;
        long lastUsedFrame;

        Entry(ClipReader reader, long frame) {
            this.reader = reader;
            this.lastUsedFrame = frame;
        }
    }

    /**
     * Composites every hole into [rgba], which is straight-alpha RGBA of
     * [width]x[height].
     *
     * <p>Mutated in place. The array came off the method channel and belongs to
     * this call — Flutter decodes a `Uint8List` into a fresh `byte[]` — so
     * unlike the Apple side there is no caller's buffer to protect.
     */
    void fill(byte[] rgba, int width, int height, List<?> holes) throws IOException {
        frameIndex++;
        for (final Object item : holes) {
            if (!(item instanceof Map)) {
                throw new IOException("hole " + item + " is not a map");
            }
            final Map<?, ?> hole = (Map<?, ?>) item;
            final Object rawPath = hole.get("path");
            if (!(rawPath instanceof String)) {
                throw new IOException("hole has no path: " + hole);
            }
            final String path = (String) rawPath;

            final ClipReader reader = readerFor(path);
            final ClipFrame frame = reader.frameAtTime(asLong(hole.get("sourceTimeUs")));
            ClipBlend.blend(rgba, width, height, frame,
                    asInt(hole.get("x")),
                    asInt(hole.get("y")),
                    asInt(hole.get("w")),
                    asInt(hole.get("h")),
                    asInt(hole.get("quarterTurns")));
        }
        evictIdle();
    }

    /** Lets every decoder go. Called when a film finishes and when one starts. */
    void release() {
        for (final Entry entry : readers.values()) {
            entry.reader.close();
        }
        readers.clear();
        frameIndex = 0;
    }

    private ClipReader readerFor(String path) throws IOException {
        final Entry existing = readers.get(path);
        if (existing != null) {
            existing.lastUsedFrame = frameIndex;
            return existing.reader;
        }
        while (readers.size() >= MAX_OPEN_READERS) {
            evictOldest();
        }
        final ClipReader opened = new ClipReader(path);
        readers.put(path, new Entry(opened, frameIndex));
        return opened;
    }

    private void evictIdle() {
        final Iterator<Map.Entry<String, Entry>> it = readers.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, Entry> e = it.next();
            if (frameIndex - e.getValue().lastUsedFrame > IDLE_FRAMES) {
                Log.i(TAG, "CLIP evict idle " + e.getKey());
                e.getValue().reader.close();
                it.remove();
            }
        }
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldestFrame = Long.MAX_VALUE;
        for (final Map.Entry<String, Entry> e : readers.entrySet()) {
            if (e.getValue().lastUsedFrame < oldestFrame) {
                oldestFrame = e.getValue().lastUsedFrame;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey == null) {
            return;
        }
        Log.i(TAG, "CLIP evict oldest " + oldestKey + " to stay under "
                + MAX_OPEN_READERS + " decoders");
        readers.remove(oldestKey).reader.close();
    }

    /**
     * Which of [paths] this device cannot composite, and why.
     *
     * <p>An empty result means every one of them opened and produced a frame.
     * This exists so a refusal can arrive before the render rather than four
     * thousand frames into it — the same reason `missingAssetsFor` runs where
     * the Export panel opens. Opening the decoder and pulling one frame is the
     * only honest test: a device can list a codec for a mime type and still
     * fail on the profile, the level or the color format.
     */
    static Map<String, String> undecodable(List<?> paths) {
        final LinkedHashMap<String, String> failures = new LinkedHashMap<>();
        for (final Object item : paths) {
            if (!(item instanceof String)) {
                continue;
            }
            final String path = (String) item;
            ClipReader reader = null;
            try {
                reader = new ClipReader(path);
                reader.frameAtTime(0L);
            } catch (Exception e) {
                final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                failures.put(path, reason);
                Log.w(TAG, "CLIP cannot decode " + path + ": " + reason);
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
        }
        return failures;
    }

    /** Every path the caller mentioned, in the order the holes named them. */
    static List<String> pathsIn(List<?> holes) {
        final ArrayList<String> paths = new ArrayList<>();
        for (final Object item : holes) {
            if (item instanceof Map) {
                final Object path = ((Map<?, ?>) item).get("path");
                if (path instanceof String && !paths.contains(path)) {
                    paths.add((String) path);
                }
            }
        }
        return paths;
    }

    /**
     * Reads a number the method channel may have sent as either width.
     *
     * <p>Flutter's standard codec picks int32 for a small `int` and int64 for a
     * large one, so the same field arrives as an Integer on one frame and a Long
     * on another as a clip's timestamp grows past 2^31 microseconds. Casting to
     * one of them works right up until the film is thirty-six minutes long.
     */
    private static long asLong(Object value) throws IOException {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IOException("expected a number, got " + value);
    }

    private static int asInt(Object value) throws IOException {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new IOException("expected a number, got " + value);
    }
}
