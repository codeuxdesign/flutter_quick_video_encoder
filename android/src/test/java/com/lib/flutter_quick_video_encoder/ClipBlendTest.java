package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * The compositing arithmetic and the geometry on top of it.
 *
 * <p>These run in a plain JVM, which is the whole reason {@link ClipBlend} and
 * {@link ClipColor} carry no android imports. The codec half of the Android clip
 * path cannot be tested without a phone; this half can, and it is the half that
 * has historically been wrong.
 *
 * <p>Every assertion here is written so that failure is reachable — the partial
 * alpha case in particular is chosen so the correct answer, the known-wrong
 * premultiplied answer, and an untouched destination are three different
 * numbers.
 */
public class ClipBlendTest {

    /** Full-range Rec.709 with neutral chroma, so a luma of g decodes to (g,g,g). */
    private static ClipFrame grayFrame(int width, int height, int... luma) {
        final byte[] y = new byte[width * height];
        for (int i = 0; i < luma.length; i++) {
            y[i] = (byte) luma[i];
        }
        final int cw = (width + 1) / 2;
        final int ch = (height + 1) / 2;
        final byte[] u = new byte[cw * ch];
        final byte[] v = new byte[cw * ch];
        Arrays.fill(u, (byte) 128);
        Arrays.fill(v, (byte) 128);
        return new ClipFrame(y, u, v, width, height,
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true), 0L,
                false);
    }

    private static byte[] filled(int width, int height, int r, int g, int b, int a) {
        final byte[] out = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            out[i * 4] = (byte) r;
            out[i * 4 + 1] = (byte) g;
            out[i * 4 + 2] = (byte) b;
            out[i * 4 + 3] = (byte) a;
        }
        return out;
    }

    private static int at(byte[] rgba, int width, int x, int y, int channel) {
        return rgba[(y * width + x) * 4 + channel] & 0xFF;
    }

    @Test
    public void aClearedHoleShowsNothingButTheClip() {
        final byte[] dst = filled(4, 4, 10, 20, 30, 0);
        final ClipFrame clip = grayFrame(4, 4,
                200, 200, 200, 200,
                200, 200, 200, 200,
                200, 200, 200, 200,
                200, 200, 200, 200);

        ClipBlend.blend(dst, 4, 4, clip, 0, 0, 4, 4, 0);

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals("red at " + x + "," + y, 200, at(dst, 4, x, y, 0));
                assertEquals("green at " + x + "," + y, 200, at(dst, 4, x, y, 1));
                assertEquals("blue at " + x + "," + y, 200, at(dst, 4, x, y, 2));
                assertEquals("alpha at " + x + "," + y, 255, at(dst, 4, x, y, 3));
            }
        }
    }

    @Test
    public void anOpaqueOverlayHidesTheClipCompletely() {
        final byte[] dst = filled(2, 2, 10, 20, 30, 255);
        final byte[] before = dst.clone();

        ClipBlend.blend(dst, 2, 2, grayFrame(2, 2, 200, 200, 200, 200), 0, 0, 2, 2, 0);

        assertTrue("an opaque frame must come back byte for byte",
                Arrays.equals(before, dst));
    }

    /**
     * The bug that cost a day on the Apple side, expressed as a number.
     *
     * <p>The destination is straight alpha, so source-over is
     * {@code d*a + s*(1-a)}. The premultiplied form, {@code d + s*(1-a)}, adds a
     * whole map to a partial clip and wraps per channel. With a destination red
     * of 250 under half alpha the three candidate answers are 225 (correct), 93
     * (premultiplied, wrapped) and 250 (never blended at all).
     */
    @Test
    public void aPartialClearWeightsTheOverlayByItsOwnAlpha() {
        final byte[] dst = filled(1, 1, 250, 0, 0, 128);

        ClipBlend.blend(dst, 1, 1, grayFrame(1, 1, 200), 0, 0, 1, 1, 0);

        assertEquals("red", (250 * 128 + 200 * 127) / 255, at(dst, 1, 0, 0, 0));
        assertEquals("red is not the premultiplied answer", 225, at(dst, 1, 0, 0, 0));
        assertEquals("green", (0 * 128 + 200 * 127) / 255, at(dst, 1, 0, 0, 1));
        assertEquals("blue", (0 * 128 + 200 * 127) / 255, at(dst, 1, 0, 0, 2));
        assertEquals("alpha ends opaque", 255, at(dst, 1, 0, 0, 3));
    }

    @Test
    public void nothingOutsideTheRectangleIsTouched() {
        final byte[] dst = filled(8, 8, 10, 20, 30, 0);

        ClipBlend.blend(dst, 8, 8, grayFrame(2, 2, 200, 200, 200, 200), 2, 3, 4, 2, 0);

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                final boolean inside = x >= 2 && x < 6 && y >= 3 && y < 5;
                final int red = at(dst, 8, x, y, 0);
                if (inside) {
                    assertEquals("inside at " + x + "," + y, 200, red);
                } else {
                    assertEquals("outside at " + x + "," + y, 10, red);
                    assertEquals("outside alpha at " + x + "," + y, 0, at(dst, 8, x, y, 3));
                }
            }
        }
    }

    @Test
    public void aRectangleThatRunsOffTheFrameIsClipped() {
        final byte[] dst = filled(4, 4, 10, 20, 30, 0);

        // Half off the right edge and half off the bottom.
        ClipBlend.blend(dst, 4, 4, grayFrame(2, 2, 200, 200, 200, 200), 2, 2, 4, 4, 0);

        assertEquals(200, at(dst, 4, 3, 3, 0));
        assertEquals(10, at(dst, 4, 1, 1, 0));
    }

    /**
     * A quarter turn maps corners, and which corner goes where is the whole
     * content of the test. Three of the four branches have never executed on any
     * platform — the corpus clip is upright — so this is the only thing standing
     * behind them.
     */
    @Test
    public void quarterTurnsMoveTheCornersClockwise() {
        // topLeft 10, topRight 20, bottomLeft 30, bottomRight 40.
        final int[] corners = {10, 20, 30, 40};

        assertCorners(corners, 0, 10, 20, 30, 40);
        assertCorners(corners, 1, 30, 10, 40, 20);
        assertCorners(corners, 2, 40, 30, 20, 10);
        assertCorners(corners, 3, 20, 40, 10, 30);
    }

    @Test
    public void quarterTurnsWrapRatherThanRunOffTheEnd() {
        final int[] corners = {10, 20, 30, 40};
        // A negative turn and a turn past three both have to land on a branch.
        assertCorners(corners, 4, 10, 20, 30, 40);
        assertCorners(corners, -1, 20, 40, 10, 30);
    }

    private static void assertCorners(int[] source, int turns,
                                      int topLeft, int topRight,
                                      int bottomLeft, int bottomRight) {
        final byte[] dst = filled(2, 2, 0, 0, 0, 0);
        ClipBlend.blend(dst, 2, 2,
                grayFrame(2, 2, source[0], source[1], source[2], source[3]),
                0, 0, 2, 2, turns);
        assertEquals("turns " + turns + " top left", topLeft, at(dst, 2, 0, 0, 0));
        assertEquals("turns " + turns + " top right", topRight, at(dst, 2, 1, 0, 0));
        assertEquals("turns " + turns + " bottom left", bottomLeft, at(dst, 2, 0, 1, 0));
        assertEquals("turns " + turns + " bottom right", bottomRight, at(dst, 2, 1, 1, 0));
    }

    /**
     * Chroma is half resolution, and the index into it is the sample position
     * shifted down — not divided by the chroma width, not the luma index.
     * Getting this wrong tilts the color across the picture, which reads as a
     * bad clip rather than as a bug.
     */
    @Test
    public void chromaIsSharedAcrossEachTwoByTwoBlock() {
        final byte[] luma = new byte[16];
        Arrays.fill(luma, (byte) 128);
        // Two chroma columns: the left one pushed red, the right one pushed blue.
        final byte[] cb = {(byte) 128, (byte) 200, (byte) 128, (byte) 200};
        final byte[] cr = {(byte) 200, (byte) 128, (byte) 200, (byte) 128};
        final ClipFrame clip = new ClipFrame(luma, cb, cr, 4, 4,
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true), 0L,
                false);

        final byte[] dst = filled(4, 4, 0, 0, 0, 0);
        ClipBlend.blend(dst, 4, 4, clip, 0, 0, 4, 4, 0);

        for (int y = 0; y < 4; y++) {
            assertEquals("columns 0 and 1 share chroma on row " + y,
                    at(dst, 4, 0, y, 0), at(dst, 4, 1, y, 0));
            assertEquals("columns 2 and 3 share chroma on row " + y,
                    at(dst, 4, 2, y, 0), at(dst, 4, 3, y, 0));
            assertTrue("the left half must be redder than the right on row " + y,
                    at(dst, 4, 0, y, 0) > at(dst, 4, 2, y, 0));
            assertTrue("the right half must be bluer than the left on row " + y,
                    at(dst, 4, 2, y, 2) > at(dst, 4, 0, y, 2));
        }
    }

    @Test
    public void aClipSmallerThanItsRectangleIsStretchedNotRepeated() {
        final byte[] dst = filled(4, 1, 0, 0, 0, 0);
        ClipBlend.blend(dst, 4, 1, grayFrame(2, 1, 60, 200), 0, 0, 4, 1, 0);

        assertEquals(60, at(dst, 4, 0, 0, 0));
        assertEquals(60, at(dst, 4, 1, 0, 0));
        assertEquals(200, at(dst, 4, 2, 0, 0));
        assertEquals(200, at(dst, 4, 3, 0, 0));
    }

    @Test
    public void anEmptyRectangleDoesNothing() {
        final byte[] dst = filled(2, 2, 10, 20, 30, 0);
        final byte[] before = dst.clone();

        ClipBlend.blend(dst, 2, 2, grayFrame(2, 2, 200, 200, 200, 200), 0, 0, 0, 2, 0);
        ClipBlend.blend(dst, 2, 2, null, 0, 0, 2, 2, 0);

        assertTrue(Arrays.equals(before, dst));
    }
}
