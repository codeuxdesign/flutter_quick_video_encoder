package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The half of the Android clip path that needs a running Android.
 *
 * <p>Everything here drives {@link ClipReader} and {@link ClipCompositor} against
 * a clip {@link SyntheticClip} writes, so the correct answer at every instant is
 * known by construction rather than by inspection. That covers the parts no
 * laptop can reach: MediaExtractor, MediaCodec, `getOutputImage` and its row and
 * pixel strides, the forward advance, and the seek.
 *
 * <p><b>An emulator is not a device.</b> It runs software codecs, so what passes
 * here says the logic is right and says nothing about a hardware decoder's color
 * formats, its concurrent-instance limit, 4K HEVC, or how long any of it takes.
 * `app/android/FIRST-DEVICE-RUN.md` is still the list that matters.
 */
@RunWith(AndroidJUnit4.class)
public class ClipReaderTest {

    private static File rec709Clip;

    @BeforeClass
    public static void writeTheClip() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        rec709Clip = new File(context.getCacheDir(), "synthetic-rec709.mp4");
        SyntheticClip.write(rec709Clip,
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED);
        assertTrue("the synthetic clip was not written", rec709Clip.length() > 0);
    }

    @AfterClass
    public static void removeTheClip() {
        if (rec709Clip != null) {
            rec709Clip.delete();
        }
    }

    /** Which source frame the reader hands back for [timeUs]. */
    private static int frameIndexAt(ClipReader reader, long timeUs) throws Exception {
        final ClipFrame frame = reader.frameAtTime(timeUs);
        assertNotNull("no frame at " + timeUs + "us", frame);
        final int gray = (frame.rgbAt(SyntheticClip.WIDTH / 2, SyntheticClip.HEIGHT / 2) >> 16)
                & 0xFF;
        final int index = SyntheticClip.frameOf(gray);
        if (index < 0) {
            fail("gray " + gray + " at " + timeUs + "us belongs to no frame of the clip");
        }
        return index;
    }

    @Test
    public void theFirstInstantGivesTheFirstFrame() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            assertEquals(0, frameIndexAt(reader, 0L));
        } finally {
            reader.close();
        }
    }

    /**
     * Playing forward, one output frame per source frame. This is the assertion
     * the mp4 frame-hash run stands in for on a real film — a stalled reader and
     * a working one look identical in any single frame.
     */
    @Test
    public void playingForwardAdvancesFrameByFrame() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            for (int i = 0; i < SyntheticClip.FRAMES; i++) {
                // Halfway into the frame, so rounding cannot decide the answer.
                final long t = SyntheticClip.frameTimeUs(i) + 50_000L;
                assertEquals("at frame " + i, i, frameIndexAt(reader, t));
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Several output frames land inside one source frame whenever the film runs
     * slower than the clip, and the reader has to hold rather than advance.
     */
    @Test
    public void aFrameIsHeldUntilTheNextOneBegins() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            assertEquals(3, frameIndexAt(reader, 300_000L));
            assertEquals(3, frameIndexAt(reader, 333_000L));
            assertEquals(3, frameIndexAt(reader, 399_999L));
            assertEquals(4, frameIndexAt(reader, 400_000L));
        } finally {
            reader.close();
        }
    }

    /**
     * The jump between the corpus clip's two ranges is eighty seconds, which is
     * what the seek exists for. Landing on the wrong frame here would show up in
     * a film as footage that starts in the wrong place — plausible, and wrong.
     */
    @Test
    public void aLargeJumpForwardLandsOnTheRightFrame() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            assertEquals(2, frameIndexAt(reader, 250_000L));
            assertEquals(45, frameIndexAt(reader, 4_550_000L));
            // And it keeps playing from there rather than only landing once.
            assertEquals(46, frameIndexAt(reader, 4_650_000L));
        } finally {
            reader.close();
        }
    }

    @Test
    public void jumpingBackwardsLandsOnTheRightFrame() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            assertEquals(40, frameIndexAt(reader, 4_050_000L));
            assertEquals(5, frameIndexAt(reader, 550_000L));
            assertEquals(6, frameIndexAt(reader, 650_000L));
        } finally {
            reader.close();
        }
    }

    /**
     * A source that has run out keeps showing its final frame rather than
     * vanishing — the alternative is a hole that goes black at the end of a clip.
     */
    @Test
    public void pastTheEndTheLastFrameIsHeld() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            assertEquals(SyntheticClip.FRAMES - 1, frameIndexAt(reader, 60_000_000L));
            assertEquals(SyntheticClip.FRAMES - 1, frameIndexAt(reader, 61_000_000L));
        } finally {
            reader.close();
        }
    }

    /**
     * What the file said about its own color, read back off it.
     *
     * <p>This is the plumbing behind the HLG conversion: the arithmetic is
     * covered by host-side tests, but nothing there proves the reader picks the
     * right {@link ClipColor} out of a real file's format.
     */
    @Test
    public void theFilesColorIsReadRatherThanAssumed() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            final ClipFrame frame = reader.frameAtTime(0L);
            assertEquals("BT.709/SDR/limited", frame.color.describe());
        } finally {
            reader.close();
        }
    }

    /** The dimensions come off the decoded image, crop and strides accounted for. */
    @Test
    public void theDecodedFrameIsTheSizeTheFileSaid() throws Exception {
        final ClipReader reader = new ClipReader(rec709Clip.getAbsolutePath());
        try {
            final ClipFrame frame = reader.frameAtTime(0L);
            assertEquals(SyntheticClip.WIDTH, frame.width);
            assertEquals(SyntheticClip.HEIGHT, frame.height);
            assertEquals(SyntheticClip.WIDTH * SyntheticClip.HEIGHT, frame.luma.length);
        } finally {
            reader.close();
        }
    }

    /**
     * The whole path the plugin actually calls: a hole map in, a composited
     * frame out, with the rectangle filled and nothing outside it touched.
     */
    @Test
    public void theCompositorFillsTheRectangleAndNothingElse() throws Exception {
        final int width = 64;
        final int height = 48;
        final byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            rgba[i * 4] = (byte) 200;      // a map-colored frame with an
            rgba[i * 4 + 1] = (byte) 100;  // opaque overlay everywhere...
            rgba[i * 4 + 2] = (byte) 50;
            rgba[i * 4 + 3] = (byte) 255;
        }
        // ...except the hole, which the painter cleared.
        for (int y = 8; y < 24; y++) {
            for (int x = 16; x < 48; x++) {
                rgba[(y * width + x) * 4 + 3] = 0;
            }
        }

        final Map<String, Object> hole = new HashMap<>();
        hole.put("path", rec709Clip.getAbsolutePath());
        hole.put("sourceTimeUs", 700_000L);
        hole.put("x", 16);
        hole.put("y", 8);
        hole.put("w", 32);
        hole.put("h", 16);
        hole.put("quarterTurns", 0);
        final List<Object> holes = new ArrayList<>();
        holes.add(hole);

        final ClipCompositor compositor = new ClipCompositor();
        try {
            compositor.fill(rgba, width, height, holes);
        } finally {
            compositor.release();
        }

        final int inside = rgba[((12 * width) + 30) * 4] & 0xFF;
        assertEquals("the hole should hold frame 7", 7, SyntheticClip.frameOf(inside));

        final int outside = rgba[((2 * width) + 2) * 4] & 0xFF;
        assertEquals("outside the hole must be untouched", 200, outside);
        assertEquals("and still opaque", 255, rgba[((2 * width) + 2) * 4 + 3] & 0xFF);
    }

    /**
     * A file nothing can decode is named, not skipped. The whole point of the
     * Android compositor is that a clip which cannot be read stops the export
     * rather than becoming a black window in it.
     */
    @Test
    public void anUnreadableSourceIsReportedByName() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File junk = new File(context.getCacheDir(), "not-really-video.mp4");
        try (FileOutputStream out = new FileOutputStream(junk)) {
            out.write("this is not an mp4".getBytes("US-ASCII"));
        }
        try {
            final List<String> paths = new ArrayList<>();
            paths.add(junk.getAbsolutePath());
            paths.add(rec709Clip.getAbsolutePath());

            final Map<String, String> failures = ClipCompositor.undecodable(paths);

            assertEquals("only the junk file should fail", 1, failures.size());
            assertTrue("the failure must name the file",
                    failures.containsKey(junk.getAbsolutePath()));
            assertNotNull("and say why", failures.get(junk.getAbsolutePath()));
        } finally {
            junk.delete();
        }
    }

    @Test
    public void aReadableSourceReportsNothing() {
        final List<String> paths = new ArrayList<>();
        paths.add(rec709Clip.getAbsolutePath());

        assertTrue("a clip that decodes must not be reported",
                ClipCompositor.undecodable(paths).isEmpty());
    }
}
