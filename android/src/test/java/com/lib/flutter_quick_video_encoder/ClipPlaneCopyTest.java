package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * The layouts `COLOR_FormatYUV420Flexible` is allowed to be.
 *
 * <p>This is the classic Android video bug, and the reason it is classic is that
 * it is invisible on whichever device the code was written on. Measured: the
 * Pixel 9 API 36 emulator hands back planar, pixel stride 1, row stride exactly
 * the width, no crop, 8-bit — the friendliest layout in the family. Eleven
 * instrumented tests pass against it without ever entering the branches that go
 * wrong on real hardware.
 *
 * <p>So the buffers here are laid out by hand: padded rows, semiplanar chroma
 * with a pixel stride of 2, a crop offset, and 10-bit samples. None of these can
 * be produced on demand from a codec, and all of them are one phone away.
 */
public class ClipPlaneCopyTest {

    /** A plane of `value(x, y)` with the given strides, plus room for a crop. */
    private static ByteBuffer plane(int rowStride, int pixelStride, int rows) {
        return ByteBuffer.allocate(rowStride * rows + pixelStride);
    }

    private interface Sample {
        int at(int x, int y);
    }

    private static void lay(ByteBuffer buffer, int rowStride, int pixelStride,
                            int left, int top, int width, int height,
                            int sampleOffset, Sample sample) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.put((top + y) * rowStride + (left + x) * pixelStride + sampleOffset,
                        (byte) sample.at(x, y));
            }
        }
    }

    @Test
    public void aPlanarPlaneWithPaddedRowsIsPackedTight() {
        final int width = 6;
        final int height = 4;
        final int rowStride = 16;   // padding a codec would add and a test never sees
        final ByteBuffer buffer = plane(rowStride, 1, height);
        lay(buffer, rowStride, 1, 0, 0, width, height, 0, (x, y) -> 10 * y + x);

        final short[] out = new short[width * height];
        ClipReader.copyPlane(buffer, rowStride, 1, out, 0, 0, width, height, false, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Samples are ten bits now, and an 8-bit source is shifted up by
                // two rather than widened — so the expectation shifts with it.
                assertEquals("at " + x + "," + y,
                        (10 * y + x) << 2, out[y * width + x] & 0x3FF);
            }
        }
    }

    /**
     * Semiplanar: chroma interleaved, so every other byte belongs to the other
     * channel. Reading it as planar gives a plane half made of the wrong chroma,
     * which tints the picture in stripes.
     */
    @Test
    public void aSemiplanarChromaPlaneSkipsTheInterleavedChannel() {
        final int width = 4;
        final int height = 3;
        final int rowStride = 12;
        final int pixelStride = 2;
        final ByteBuffer buffer = plane(rowStride, pixelStride, height);
        lay(buffer, rowStride, pixelStride, 0, 0, width, height, 0, (x, y) -> 10 * y + x);
        // The channel we must not read, laid over the gaps.
        lay(buffer, rowStride, pixelStride, 0, 0, width, height, 1, (x, y) -> 200);

        final short[] out = new short[width * height];
        ClipReader.copyPlane(buffer, rowStride, pixelStride, out, 0, 0, width, height, false, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Samples are ten bits now, and an 8-bit source is shifted up by
                // two rather than widened — so the expectation shifts with it.
                assertEquals("at " + x + "," + y,
                        (10 * y + x) << 2, out[y * width + x] & 0x3FF);
            }
        }
    }

    /**
     * A crop rectangle is how a decoder says the picture is smaller than the
     * buffer it coded — 300x188 becomes 304x192 in macroblocks. Ignoring the
     * offset shifts the whole clip diagonally.
     */
    @Test
    public void aCropOffsetIsAppliedToBothAxes() {
        final int width = 3;
        final int height = 2;
        final int rowStride = 10;
        final ByteBuffer buffer = plane(rowStride, 1, 8);
        // Fill everything with a value the crop must exclude, then the real
        // picture inside it.
        lay(buffer, rowStride, 1, 0, 0, 10, 8, 0, (x, y) -> 250);
        lay(buffer, rowStride, 1, 2, 3, width, height, 0, (x, y) -> 10 * y + x);

        final short[] out = new short[width * height];
        ClipReader.copyPlane(buffer, rowStride, 1, out, 2, 3, width, height, false, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Samples are ten bits now, and an 8-bit source is shifted up by
                // two rather than widened — so the expectation shifts with it.
                assertEquals("at " + x + "," + y,
                        (10 * y + x) << 2, out[y * width + x] & 0x3FF);
            }
        }
    }

    /**
     * 10-bit output stores each sample in two little-endian bytes, left-aligned.
     *
     * <p><b>The low byte is precision, not padding, and this used to throw it
     * away.</b> Taking the high byte alone gave an 8-bit sample from a ten-bit
     * source — the frame still decoded, still converted and still looked
     * plausible, two bits short, which is the only reason it survived. The
     * expectation below carries those two bits explicitly: the value is the
     * whole word shifted down by six, so the `0xC0` in the low byte contributes
     * a 3 that a high-byte read cannot produce.
     */
    @Test
    public void tenBitSamplesKeepTheLowBitsToo() {
        final int width = 4;
        final int height = 2;
        final int rowStride = 16;
        final int pixelStride = 2;
        final ByteBuffer buffer = plane(rowStride, pixelStride, height);
        lay(buffer, rowStride, pixelStride, 0, 0, width, height, 1, (x, y) -> 10 * y + x);
        // Low bits, which carry the two extra bits of precision and must not be
        // mistaken for the value.
        lay(buffer, rowStride, pixelStride, 0, 0, width, height, 0, (x, y) -> 0xC0);

        final short[] out = new short[width * height];
        ClipReader.copyPlane(buffer, rowStride, pixelStride, out, 0, 0, width, height, true, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // ((hi << 8) | lo) >> 6 — the trailing 3 is the low byte's 0xC0,
                // and is exactly what a high-byte-only read would lose.
                assertEquals("at " + x + "," + y,
                        ((10 * y + x) << 2) | 3, out[y * width + x] & 0x3FF);
            }
        }
    }

    @Test
    public void tenBitChromaCarriesBothACropAndAWiderPixelStride() {
        final int width = 3;
        final int height = 2;
        final int rowStride = 24;
        final int pixelStride = 4;   // P010 chroma: interleaved, two bytes a sample
        final ByteBuffer buffer = plane(rowStride, pixelStride, 6);
        lay(buffer, rowStride, pixelStride, 0, 0, 5, 6, 1, (x, y) -> 250);
        lay(buffer, rowStride, pixelStride, 1, 2, width, height, 1, (x, y) -> 10 * y + x);

        final short[] out = new short[width * height];
        ClipReader.copyPlane(buffer, rowStride, pixelStride, out, 1, 2, width, height, true, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Samples are ten bits now, and an 8-bit source is shifted up by
                // two rather than widened — so the expectation shifts with it.
                assertEquals("at " + x + "," + y,
                        (10 * y + x) << 2, out[y * width + x] & 0x3FF);
            }
        }
    }

    /**
     * An 8-bit clip has to come back out as the 8-bit clip it went in as.
     *
     * <p><b>Both ranges, because they widen by different rules and one rule for
     * both is a silent one-code darkening.</b> Limited range is an exact shift;
     * full range is not, and shifting there leaves white at 254 and every
     * non-zero sample one low — over a whole clip, with nothing logged. The
     * corpus contains full-range files, so this is reachable rather than
     * theoretical: the action-cam footage is `yuvj420p`.
     *
     * <p>Every code, not a sample of them. The failure this guards against is
     * uniform, so a handful of spot checks would catch it — but a widening that
     * is wrong for only part of the range is the more likely future mistake, and
     * 256 values is free.
     */
    @Test
    public void anEightBitPlaneRoundTripsExactlyInBothRanges() {
        for (int i = 0; i < 2; i++) {
            final boolean fullRange = i == 1;
            final int low = fullRange ? 0 : 16;
            final int high = fullRange ? 255 : 235;
            final int count = high - low + 1;

            final ByteBuffer buffer = plane(count, 1, 1);
            lay(buffer, count, 1, 0, 0, count, 1, 0, (x, y) -> low + x);

            final short[] out = new short[count];
            ClipReader.copyPlane(buffer, count, 1, out, 0, 0, count, 1, false, fullRange);

            final ClipColor color = new ClipColor(
                    ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, fullRange);
            for (int v = low; v <= high; v++) {
                final int decoded =
                        (color.toRgb(out[v - low] & 0x3FF, 512, 512) >> 16) & 0xFF;
                // Full range must come back as itself — that is what the ceiling
                // widening buys. Limited range expands 16..235 onto 0..255, so
                // the invariant is not identity but *agreement with the 8-bit
                // path*: shifting by two leaves (4v-64)/876 equal to (v-16)/219,
                // and the decode floors both the same way.
                final int expected = fullRange
                        ? v
                        : (int) ((v - 16) * 255.0 / 219.0);
                assertEquals((fullRange ? "full" : "limited") + " range code " + v
                                + " widened to " + (out[v - low] & 0x3FF)
                                + " and decoded to " + decoded,
                        expected, decoded);
            }
        }
    }

    /**
     * The layout every device tested so far actually produces, kept so the fast
     * path is covered as well as the ones that are not.
     */
    @Test
    public void theFriendlyLayoutStillWorks() {
        final int width = 5;
        final int height = 3;
        final ByteBuffer buffer = plane(width, 1, height);
        lay(buffer, width, 1, 0, 0, width, height, 0, (x, y) -> 10 * y + x);

        final short[] out = new short[width * height];
        ClipReader.copyPlane(buffer, width, 1, out, 0, 0, width, height, false, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Samples are ten bits now, and an 8-bit source is shifted up by
                // two rather than widened — so the expectation shifts with it.
                assertEquals("at " + x + "," + y,
                        (10 * y + x) << 2, out[y * width + x] & 0x3FF);
            }
        }
    }
}
