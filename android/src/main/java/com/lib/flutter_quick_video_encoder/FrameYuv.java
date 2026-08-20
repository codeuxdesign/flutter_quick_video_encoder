package com.lib.flutter_quick_video_encoder;

import java.nio.ByteBuffer;

/**
 * The rendered frame on its way into the encoder: RGBA to planar YUV 4:2:0.
 *
 * <p><b>Rec.709, limited range, and stated rather than left to the player.</b>
 * This conversion used to be Rec.601 — the {@code 66/129/25} coefficients — with
 * nothing written into the file to say so. Every player treats an HD stream that
 * declares no color as Rec.709, so the whole film came back through a matrix it
 * was not encoded with: greens pulled one way, reds the other, subtly and
 * everywhere. It is not visible on its own, only against a reference, which is
 * why it survived. It matters now because there is a reference — the same film
 * rendered on macOS through AVFoundation, which tags its output correctly — and
 * because a clip composited into a frame that is then mis-encoded would read as
 * a bug in the clip.
 *
 * <p>The draft target is 540x960, under the 720-line boundary at which some
 * players switch their default to Rec.601 instead. That is the reason the
 * encoder is also told, through {@code KEY_COLOR_STANDARD}, rather than trusting
 * the default to be the same one at every output size.
 *
 * <p>No android imports, so the arithmetic is testable on the host against the
 * inverse in {@link ClipColor}.
 */
final class FrameYuv {

    private FrameYuv() {
    }

    // Rec.709 luma coefficients scaled to the limited range's 219 codes, in
    // 16.16 fixed point. 8-bit fixed point is one code short on white; this is
    // exact.
    private static final int Y_R = 11966;
    private static final int Y_G = 40254;
    private static final int Y_B = 4064;

    private static final int CB_R = -6596;
    private static final int CB_G = -22189;
    private static final int CB_B = 28786;

    private static final int CR_R = 28787;
    private static final int CR_G = -26147;
    private static final int CR_B = -2640;

    private static final int HALF = 1 << 15;

    /**
     * Converts a straight-alpha RGBA frame to planar I420: all of Y, then all of
     * U, then all of V.
     *
     * <p>Alpha is dropped, which is correct here and is also why the clip
     * compositor has to run before this: after this point the information that
     * said "this rectangle belongs to a clip" is gone.
     */
    static byte[] toYuv420Planar(byte[] rgba, int width, int height) {
        return toYuv420Planar(rgba, width, height, null);
    }

    /**
     * As above, into [reuse] when it is the right size.
     *
     * <p>A 1080x1920 frame is three megabytes of planes, and a full film is
     * 4339 of them — thirteen gigabytes handed to the collector to describe
     * pictures that are each thrown away a millisecond later. The encoder feeds
     * one frame at a time, so one buffer is enough.
     */
    static byte[] toYuv420Planar(byte[] rgba, int width, int height, byte[] reuse) {
        final int frameSize = width * height;
        final int needed = frameSize * 3 / 2;
        final byte[] out = reuse != null && reuse.length == needed
                ? reuse : new byte[needed];

        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + (frameSize / 4);

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                final int p = (j * width + i) * 4;
                final int r = rgba[p] & 0xFF;
                final int g = rgba[p + 1] & 0xFF;
                final int b = rgba[p + 2] & 0xFF;

                out[yIndex++] = clamp(((Y_R * r + Y_G * g + Y_B * b + HALF) >> 16) + 16);
                if (j % 2 == 0 && i % 2 == 0) {
                    // The top-left sample of each 2x2 block rather than their
                    // average. Averaging would be better and would also change
                    // every frame this encoder has ever written, so it is left
                    // alone deliberately.
                    out[uIndex++] = clamp(((CB_R * r + CB_G * g + CB_B * b + HALF) >> 16) + 128);
                    out[vIndex++] = clamp(((CR_R * r + CR_G * g + CR_B * b + HALF) >> 16) + 128);
                }
            }
        }

        return out;
    }

    /**
     * Copies planar I420 into an encoder's input planes, bulk by row.
     *
     * <p><b>Row at a time, not sample at a time.</b> This was three million
     * `ByteBuffer.put(index, byte)` calls per 1080x1920 frame — one per luma
     * sample and one per chroma sample — and each is a bounds-checked access on
     * a direct buffer that the JIT will not fold into a copy. The same shape
     * cost half the clip compositor's budget until it was measured; see
     * `ClipReader.copyPlane`, which is the mirror image of this on the way in.
     *
     * <p>Takes buffers and strides rather than an `Image` so it can be measured
     * and tested without a codec. An encoder's input is normally planar with a
     * pixel stride of one, which is the bulk path; the per-sample loop stays for
     * the semiplanar encoders that exist.
     */
    static void fillPlanes(byte[] yuv420, int width, int height,
                           ByteBuffer y, int yRowStride, int yPixelStride,
                           ByteBuffer u, int uRowStride, int uPixelStride,
                           ByteBuffer v, int vRowStride, int vPixelStride) {
        final int frameSize = width * height;
        final int chromaWidth = width / 2;
        final int chromaHeight = height / 2;
        writePlane(y, yRowStride, yPixelStride, yuv420, 0, width, height);
        writePlane(u, uRowStride, uPixelStride, yuv420, frameSize,
                chromaWidth, chromaHeight);
        writePlane(v, vRowStride, vPixelStride, yuv420,
                frameSize + chromaWidth * chromaHeight, chromaWidth, chromaHeight);
    }

    private static void writePlane(ByteBuffer dst, int rowStride, int pixelStride,
                                   byte[] src, int offset, int width, int height) {
        if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                dst.position(row * rowStride);
                dst.put(src, offset + row * width, width);
            }
            return;
        }
        // **Semiplanar chroma, which is what a real phone hands back and what
        // the bulk fix above never covered.** A Galaxy S24 Ultra reports
        // `y(px=1) u(px=2) v(px=2)` — so luma took the memcpy above and both
        // chroma planes fell through to a per-sample loop, 518,400
        // bounds-checked puts each. Measured on device, that loop *was* the
        // whole remaining cost of the fill: 16.0 ns a put over 1,036,800 chroma
        // samples is 16.6 ms against 17.1 ms measured, leaving under a
        // millisecond for luma's 2 MB memcpy. The feed was reported fixed and
        // was fixed for one plane of three.
        //
        // **Not a straight mirror of `ClipReader.copyPlane`, and the asymmetry
        // is the whole difficulty.** Reading, the bytes between the samples are
        // simply ignored; writing, they belong to the *other* chroma plane and
        // a bulk put over the span would clobber them. So each row is read back
        // first, the new samples are scattered into it in plain array space —
        // where a store is a store rather than a bounds-checked buffer access —
        // and the row goes back as one put. Both planes are written, so every
        // interleaved byte is somebody's, and whichever runs second reads what
        // the first one left.
        final int span = (width - 1) * pixelStride + 1;
        final byte[] row = new byte[span];
        for (int y = 0; y < height; y++) {
            final int base = y * rowStride;
            final int from = offset + y * width;
            // A final row the buffer does not have room to span — a padded
            // layout can end early — falls back rather than throwing.
            if (base + span > dst.limit()) {
                for (int col = 0; col < width; col++) {
                    dst.put(base + col * pixelStride, src[from + col]);
                }
                continue;
            }
            dst.position(base);
            dst.get(row, 0, span);
            for (int col = 0; col < width; col++) {
                row[col * pixelStride] = src[from + col];
            }
            dst.position(base);
            dst.put(row, 0, span);
        }
    }

    private static byte clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return (byte) (value > 255 ? 255 : value);
    }
}
