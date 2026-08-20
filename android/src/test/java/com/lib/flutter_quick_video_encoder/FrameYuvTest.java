package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rendered frame's trip into the encoder, checked against the trip a clip
 * takes on the way in.
 *
 * <p>{@link FrameYuv} and {@link ClipColor} are inverses of each other under
 * Rec.709 limited range, and that is the useful property: a round trip that
 * comes back where it started proves both directions agree on the same matrix.
 * The previous encoder used Rec.601 coefficients and declared nothing, so the
 * round trip through the player's Rec.709 assumption did not close — invisibly,
 * because nothing was there to compare it against.
 */
public class FrameYuvTest {

    private static final ClipColor REC709_LIMITED =
            new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false);

    /** One uniform color through the encoder's conversion and back out. */
    private static int[] roundTrip(int r, int g, int b) {
        final int width = 2;
        final int height = 2;
        final byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            rgba[i * 4] = (byte) r;
            rgba[i * 4 + 1] = (byte) g;
            rgba[i * 4 + 2] = (byte) b;
            rgba[i * 4 + 3] = (byte) 255;
        }

        final byte[] yuv = FrameYuv.toYuv420Planar(rgba, width, height);
        final int frameSize = width * height;
        final int y = yuv[0] & 0xFF;
        final int u = yuv[frameSize] & 0xFF;
        final int v = yuv[frameSize + frameSize / 4] & 0xFF;

        // `FrameYuv` writes the 8-bit planes the encoder takes; `ClipColor` now
        // reads ten-bit codes. Limited range shifts exactly — 16 to 64 and 235
        // to 940 — so this is the same round trip, not a rescaled one.
        final int rgb = REC709_LIMITED.toRgb(y << 2, u << 2, v << 2);
        return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
    }

    private static void assertRoundTrips(int r, int g, int b, int tolerance) {
        final int[] out = roundTrip(r, g, b);
        final String what = "(" + r + "," + g + "," + b + ") came back as ("
                + out[0] + "," + out[1] + "," + out[2] + ")";
        assertTrue(what, Math.abs(out[0] - r) <= tolerance);
        assertTrue(what, Math.abs(out[1] - g) <= tolerance);
        assertTrue(what, Math.abs(out[2] - b) <= tolerance);
    }

    @Test
    public void blackAndWhiteLandOnTheRangeLimits() {
        final byte[] rgba = new byte[4 * 4];
        // Two black pixels then two white ones, so both ends are in one frame.
        for (int i = 2; i < 4; i++) {
            rgba[i * 4] = (byte) 255;
            rgba[i * 4 + 1] = (byte) 255;
            rgba[i * 4 + 2] = (byte) 255;
        }
        final byte[] yuv = FrameYuv.toYuv420Planar(rgba, 2, 2);

        assertEquals("black luma", 16, yuv[0] & 0xFF);
        assertEquals("white luma", 235, yuv[2] & 0xFF);
    }

    @Test
    public void neutralsCarryNoChroma() {
        for (final int level : new int[]{0, 64, 128, 200, 255}) {
            final byte[] rgba = new byte[2 * 2 * 4];
            for (int i = 0; i < 4; i++) {
                rgba[i * 4] = (byte) level;
                rgba[i * 4 + 1] = (byte) level;
                rgba[i * 4 + 2] = (byte) level;
            }
            final byte[] yuv = FrameYuv.toYuv420Planar(rgba, 2, 2);
            assertEquals("grey " + level + " must not tint blue", 128, yuv[4] & 0xFF);
            assertEquals("grey " + level + " must not tint red", 128, yuv[5] & 0xFF);
        }
    }

    @Test
    public void aFrameRoundTripsThroughRec709() {
        assertRoundTrips(0, 0, 0, 1);
        assertRoundTrips(255, 255, 255, 1);
        assertRoundTrips(128, 128, 128, 1);
        // The map's own colors: a track red, a water blue, a terrain green.
        assertRoundTrips(214, 69, 42, 2);
        assertRoundTrips(36, 92, 158, 2);
        assertRoundTrips(88, 140, 74, 2);
    }

    /**
     * The Rec.601 coefficients this encoder used to carry, so the test says what
     * the change is worth rather than only that the new numbers agree with
     * themselves. A saturated color read through the wrong matrix moves by
     * roughly a dozen codes — small enough to survive review, large enough to
     * see beside a reference frame.
     */
    @Test
    public void theOldRec601MatrixWouldNotHaveRoundTripped() {
        final int r = 214;
        final int g = 69;
        final int b = 42;

        final int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
        final int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
        final int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

        // `FrameYuv` writes the 8-bit planes the encoder takes; `ClipColor` now
        // reads ten-bit codes. Limited range shifts exactly — 16 to 64 and 235
        // to 940 — so this is the same round trip, not a rescaled one.
        final int rgb = REC709_LIMITED.toRgb(y << 2, u << 2, v << 2);
        final int outR = (rgb >> 16) & 0xFF;
        final int outG = (rgb >> 8) & 0xFF;
        final int outB = rgb & 0xFF;

        final int drift = Math.abs(outR - r) + Math.abs(outG - g) + Math.abs(outB - b);
        assertTrue("601 encoded and 709 decoded drifted only " + drift
                + " codes, so this test proves nothing", drift >= 10);
    }

    @Test
    public void theOutputIsPlanarAndHalfAgainAsLongAsTheLuma() {
        final byte[] yuv = FrameYuv.toYuv420Planar(new byte[8 * 6 * 4], 8, 6);
        assertEquals(8 * 6 * 3 / 2, yuv.length);
    }

    /**
     * Semiplanar chroma: the layout a real phone hands back, and the one the
     * bulk fill did not cover for a month.
     *
     * <p><b>There was no semiplanar case in this file at all</b>, which is why
     * it survived: every host test wrote a planar layout, took the memcpy
     * branch, and passed — while a Galaxy S24 Ultra reports
     * {@code u(px=2) v(px=2)} and sent both chroma planes down the per-sample
     * loop the branch exists to avoid. On device that loop was the entire
     * remaining cost of the fill.
     *
     * <p><b>The assertion that matters is the one about bytes nobody wrote.</b>
     * U and V interleave into the same memory, so a bulk write over U's span
     * would flatten V — and the film would come back with its color wrong in a
     * way this project has already learned is invisible except against a
     * reference. The buffer is pre-poisoned, both planes are written, and the
     * result is compared byte for byte against the per-sample loop that was
     * always correct.
     */
    @Test
    public void semiplanarChromaMatchesThePerSampleFill() {
        final int width = 64;
        final int height = 32;
        final int chromaWidth = width / 2;
        final int chromaHeight = height / 2;
        final int rowStride = 80;
        final byte[] yuv = new byte[width * height * 3 / 2];
        for (int i = 0; i < yuv.length; i++) {
            yuv[i] = (byte) (i * 7 + 13);
        }

        final byte[] bulk = semiplanarFill(yuv, width, height, rowStride, false, POISON);
        final byte[] reference =
                semiplanarFill(yuv, width, height, rowStride, true, POISON);

        assertArrayEquals("the interleaved chroma plane", reference, bulk);

        // **Every chroma byte was written by somebody, proved by poisoning
        // twice rather than by looking for a sentinel.** Checking `!= POISON`
        // is what this did first and it is wrong for a reason worth keeping:
        // the source here is `i * 7 + 13`, which takes the value 0xA5 every 256
        // samples, so real data reads as untouched memory. Two runs under
        // different poison agree exactly where a byte was written and differ
        // everywhere it was not, whatever the data happens to contain.
        final byte[] other = semiplanarFill(yuv, width, height, rowStride, false, OTHER);
        final int lumaBytes = rowStride * height;
        for (int y = 0; y < chromaHeight; y++) {
            for (int x = 0; x < chromaWidth * 2; x++) {
                final int at = lumaBytes + y * rowStride + x;
                assertEquals("chroma byte " + at + " kept its poison, so nothing"
                        + " wrote it", bulk[at], other[at]);
            }
        }
    }

    private static final byte POISON = (byte) 0xA5;
    private static final byte OTHER = (byte) 0x5A;

    /**
     * One NV12-shaped buffer with both chroma planes written into it.
     *
     * <p>`perSample` picks the loop: true forces the original scatter by asking
     * for a layout the bulk path declines, false is what the plugin now runs.
     * Both are handed identical, poisoned memory.
     */
    private static byte[] semiplanarFill(byte[] yuv, int width, int height,
                                         int rowStride, boolean perSample,
                                         byte poison) {
        final int chromaWidth = width / 2;
        final int chromaHeight = height / 2;
        final int lumaBytes = rowStride * height;
        final byte[] backing = new byte[lumaBytes + rowStride * chromaHeight];
        java.util.Arrays.fill(backing, poison);
        final java.nio.ByteBuffer y =
                java.nio.ByteBuffer.wrap(backing, 0, lumaBytes).slice();
        // U at the plane's first byte, V at its second — NV12, the two of them
        // sharing every row.
        final java.nio.ByteBuffer u = java.nio.ByteBuffer
                .wrap(backing, lumaBytes, rowStride * chromaHeight).slice();
        final java.nio.ByteBuffer v = java.nio.ByteBuffer
                .wrap(backing, lumaBytes + 1, rowStride * chromaHeight - 1).slice();

        if (perSample) {
            final int frameSize = width * height;
            writePerSample(y, rowStride, 1, yuv, 0, width, height);
            writePerSample(u, rowStride, 2, yuv, frameSize, chromaWidth, chromaHeight);
            writePerSample(v, rowStride, 2, yuv,
                    frameSize + chromaWidth * chromaHeight, chromaWidth, chromaHeight);
        } else {
            FrameYuv.fillPlanes(yuv, width, height, y, rowStride, 1,
                    u, rowStride, 2, v, rowStride, 2);
        }
        return backing;
    }

    /** The fill as it was written, kept here as the thing to match. */
    private static void writePerSample(java.nio.ByteBuffer dst, int rowStride,
                                       int pixelStride, byte[] src, int offset,
                                       int width, int height) {
        for (int row = 0; row < height; row++) {
            final int base = row * rowStride;
            final int from = offset + row * width;
            for (int col = 0; col < width; col++) {
                dst.put(base + col * pixelStride, src[from + col]);
            }
        }
    }
}
