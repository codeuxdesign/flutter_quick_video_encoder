package com.lib.flutter_quick_video_encoder;

/**
 * One decoded clip frame, downsampled and converted for a widget to draw.
 *
 * <p>This is the preview half of the bargain {@link ClipBlend} keeps for the
 * export: same frame, same {@link ClipColor} conversion, same nearest-neighbor
 * sampling, same arithmetic. It is deliberately not a shortcut past any of them
 * — a rider drags a trim handle against this picture and then publishes what the
 * export draws, so a preview that is a shade different, or a frame early, is
 * worse than no preview at all.
 *
 * <p><b>Blending into a cleared rectangle is what this is, minus the
 * rectangle.</b> {@code ClipBlend.blend} into a fully transparent destination
 * reduces to {@code dst = rgb} — the alpha weighting cancels — so the two
 * routines produce byte-identical pixels for the same frame at the same size,
 * and {@code ClipPreviewTest} asserts exactly that rather than trusting the
 * duplication. The reason they are two routines at all is that the export
 * composites into somebody else's buffer at an offset and this one allocates its
 * own; sharing the loop would mean passing four arguments that are always the
 * same.
 *
 * <p>No android imports, so the sampling and the conversion can be tested in a
 * plain JVM.
 */
final class ClipPreview {

    private ClipPreview() {
    }

    /** Straight RGBA, row major, no padding, and the size it really came out. */
    static final class Image {
        final byte[] rgba;
        final int width;
        final int height;

        Image(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * [frame] as RGBA, with its longer side capped at [maxEdge].
     *
     * <p>A frame already inside the cap is copied at its own size rather than
     * scaled up: nearest-neighbor magnification would produce a blockier picture
     * than the widget's own filtering will, and it would cost bytes on the
     * method channel to do it.
     *
     * <p>Returns null for a frame with no usable area, which is the same answer
     * the reader gives for a clip it could not decode — one null rather than a
     * zero-sized image nobody checks for.
     */
    static Image from(ClipFrame frame, int maxEdge) {
        if (frame == null || frame.width <= 0 || frame.height <= 0 || maxEdge <= 0) {
            return null;
        }
        final int srcW = frame.width;
        final int srcH = frame.height;
        final int longest = Math.max(srcW, srcH);
        final int outW = scaled(srcW, longest, maxEdge);
        final int outH = scaled(srcH, longest, maxEdge);

        final byte[] rgba = new byte[outW * outH * 4];
        for (int y = 0; y < outH; y++) {
            // The half-pixel offset and the truncating cast are copied from
            // `ClipBlend`, not re-derived. Sampling the same source at the same
            // destination size has to pick the same source pixel, and rounding
            // where the other floors would shift the preview half a pixel
            // against the film at exactly the sizes where anyone would notice.
            final float v = (y + 0.5f) / outH;
            int sy = (int) (v * srcH);
            if (sy < 0) {
                sy = 0;
            } else if (sy >= srcH) {
                sy = srcH - 1;
            }
            for (int x = 0; x < outW; x++) {
                final float u = (x + 0.5f) / outW;
                int sx = (int) (u * srcW);
                if (sx < 0) {
                    sx = 0;
                } else if (sx >= srcW) {
                    sx = srcW - 1;
                }

                final int rgb = frame.rgbAt(sx, sy);
                final int di = (y * outW + x) * 4;
                rgba[di] = (byte) ((rgb >> 16) & 0xFF);
                rgba[di + 1] = (byte) ((rgb >> 8) & 0xFF);
                rgba[di + 2] = (byte) (rgb & 0xFF);
                rgba[di + 3] = (byte) 255;
            }
        }
        return new Image(rgba, outW, outH);
    }

    /** One edge of the frame, scaled so that [longest] lands on [maxEdge]. */
    /**
     * The output edge this would choose, exposed so the sampler can choose the
     * same one.
     *
     * <p><b>Shared rather than re-derived, because the two must agree exactly.**
     * `ClipReader`'s preview sampler gathers a frame at this size and [from]
     * then runs over it at its own size — identity sampling, byte-identical to
     * the full-resolution path. One pixel of disagreement here and it is not
     * identity any more, and the preview quietly stops matching the export.
     */
    static int outputEdge(int edge, int longest, int maxEdge) {
        return scaled(edge, longest, maxEdge);
    }

    private static int scaled(int edge, int longest, int maxEdge) {
        if (longest <= maxEdge) {
            return edge;
        }
        // Rounded rather than floored, so a 16:9 frame stays as close to 16:9 as
        // integers allow. Floored at one, because a frame wider than 512 times
        // its own height would otherwise scale its short edge to nothing and
        // hand back an empty array.
        final long scaled = Math.round((double) edge * maxEdge / longest);
        return (int) Math.max(1L, scaled);
    }
}
