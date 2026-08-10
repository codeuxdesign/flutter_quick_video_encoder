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

        final byte[] out = new byte[width * height];
        ClipReader.copyPlane(buffer, rowStride, 1, out, 0, 0, width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals("at " + x + "," + y, 10 * y + x, out[y * width + x] & 0xFF);
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

        final byte[] out = new byte[width * height];
        ClipReader.copyPlane(buffer, rowStride, pixelStride, out, 0, 0, width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals("at " + x + "," + y, 10 * y + x, out[y * width + x] & 0xFF);
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

        final byte[] out = new byte[width * height];
        ClipReader.copyPlane(buffer, rowStride, 1, out, 2, 3, width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals("at " + x + "," + y, 10 * y + x, out[y * width + x] & 0xFF);
            }
        }
    }

    /**
     * 10-bit output stores each sample in two little-endian bytes with the data
     * left-aligned, so the high byte is the 8-bit value. Reading the low byte
     * instead gives noise that looks like a corrupt decode.
     */
    @Test
    public void tenBitSamplesAreReadFromTheHighByte() {
        final int width = 4;
        final int height = 2;
        final int rowStride = 16;
        final int pixelStride = 2;
        final ByteBuffer buffer = plane(rowStride, pixelStride, height);
        lay(buffer, rowStride, pixelStride, 0, 0, width, height, 1, (x, y) -> 10 * y + x);
        // Low bits, which carry the two extra bits of precision and must not be
        // mistaken for the value.
        lay(buffer, rowStride, pixelStride, 0, 0, width, height, 0, (x, y) -> 0xC0);

        final byte[] out = new byte[width * height];
        ClipReader.copyPlane(buffer, rowStride, pixelStride, out, 0, 0, width, height, true);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals("at " + x + "," + y, 10 * y + x, out[y * width + x] & 0xFF);
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

        final byte[] out = new byte[width * height];
        ClipReader.copyPlane(buffer, rowStride, pixelStride, out, 1, 2, width, height, true);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals("at " + x + "," + y, 10 * y + x, out[y * width + x] & 0xFF);
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

        final byte[] out = new byte[width * height];
        ClipReader.copyPlane(buffer, width, 1, out, 0, 0, width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals("at " + x + "," + y, 10 * y + x, out[y * width + x] & 0xFF);
            }
        }
    }
}
