package com.lib.flutter_quick_video_encoder;

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
        final int frameSize = width * height;
        final byte[] out = new byte[frameSize * 3 / 2];

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

    private static byte clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return (byte) (value > 255 ? 255 : value);
    }
}
