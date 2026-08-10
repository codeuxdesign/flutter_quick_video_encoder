package com.lib.flutter_quick_video_encoder;

/**
 * Composites one clip frame under the rendered overlay, inside one rectangle.
 *
 * <p><b>Source-over, and the overlay is straight alpha.</b> The painter cleared
 * the rectangle, so the overlay is transparent there and
 * {@code out = over + clip*(1-a)} reduces to the clip in the middle while
 * blending correctly along the antialiased edge. That is the whole reason the
 * rectangle is cleared rather than skipped: a skipped rectangle would still hold
 * the basemap, and this arithmetic would then blend the clip with a map nobody
 * wants to see.
 *
 * <p>Nearest-neighbor sampling, matching the Apple side. The destination is
 * smaller than a 4K source by a wide margin, so this is a downscale, and a
 * bilinear tap would cost four reads per pixel to soften an image the encoder is
 * about to requantize anyway. Worth revisiting only if a clip is ever scaled up.
 */
final class ClipBlend {

    private ClipBlend() {
    }

    /**
     * Blends [clip] into [dst] inside the rectangle at ([rx],[ry]) sized
     * [rw]x[rh].
     *
     * <p>[dst] is the frame as Dart sent it: straight-alpha RGBA, row major, no
     * padding. [quarterTurns] is how far the source has to turn clockwise to be
     * upright; the rectangle is already the turned shape, because the Dart side
     * applied the rotation when it worked out the display aspect, so only the
     * sampling has to know about it.
     */
    static void blend(byte[] dst, int frameWidth, int frameHeight,
                      ClipFrame clip, int rx, int ry, int rw, int rh,
                      int quarterTurns) {
        if (clip == null || rw <= 0 || rh <= 0) {
            return;
        }
        final int srcW = clip.width;
        final int srcH = clip.height;
        if (srcW <= 0 || srcH <= 0) {
            return;
        }

        final int turns = ((quarterTurns % 4) + 4) % 4;

        for (int y = 0; y < rh; y++) {
            final int dy = ry + y;
            if (dy < 0 || dy >= frameHeight) {
                continue;
            }
            for (int x = 0; x < rw; x++) {
                final int dx = rx + x;
                if (dx < 0 || dx >= frameWidth) {
                    continue;
                }

                final int di = (dy * frameWidth + dx) * 4;

                // Read alpha before doing any decode work. A fully opaque
                // overlay pixel hides the clip completely, and inside a hole
                // that is most of the border — skipping it here is what keeps
                // the color conversion off the pixels nobody sees.
                final int a = dst[di + 3] & 0xFF;
                if (a == 255) {
                    continue;
                }

                final float u = (x + 0.5f) / rw;
                final float v = (y + 0.5f) / rh;
                final float su;
                final float sv;
                switch (turns) {
                    case 1:
                        su = v;
                        sv = 1.0f - u;
                        break;
                    case 2:
                        su = 1.0f - u;
                        sv = 1.0f - v;
                        break;
                    case 3:
                        su = 1.0f - v;
                        sv = u;
                        break;
                    default:
                        su = u;
                        sv = v;
                        break;
                }
                int sx = (int) (su * srcW);
                int sy = (int) (sv * srcH);
                if (sx < 0) {
                    sx = 0;
                } else if (sx >= srcW) {
                    sx = srcW - 1;
                }
                if (sy < 0) {
                    sy = 0;
                } else if (sy >= srcH) {
                    sy = srcH - 1;
                }

                final int rgb = clip.rgbAt(sx, sy);

                // The overlay is weighted by its own alpha, and that word is
                // the whole bug this line carried on the Apple side for a day.
                // `d + s * inv` is the *premultiplied* form of source-over,
                // correct only when the destination's color has already been
                // multiplied by its coverage. `renderSingleFrame` reads back
                // `rawStraightRgba` on purpose, so a half-cleared pixel still
                // holds its color at full strength and half the clip was being
                // *added* to a whole map. Past 255 each channel wrapped
                // independently, which is why it did not read as washed out but
                // as magenta and cyan confetti.
                //
                // It was invisible for as long as a hole was cleared outright:
                // alpha was only ever 0 or 255, and at 0 the painter had zeroed
                // the color too, so the two formulas agree. A *partial* clear
                // to crossfade a clip is what made a fractional alpha possible,
                // and the corruption appeared at exactly the fades.
                //
                // `a + inv == 255`, so this cannot overflow by construction.
                final int inv = 255 - a;
                // Destination and source are both RGB here — unlike the Apple
                // side, where the clip arrives BGRA and the channels cross over.
                // Porting those indices across would swap red and blue.
                dst[di] = (byte) (((dst[di] & 0xFF) * a + ((rgb >> 16) & 0xFF) * inv) / 255);
                dst[di + 1] = (byte) (((dst[di + 1] & 0xFF) * a + ((rgb >> 8) & 0xFF) * inv) / 255);
                dst[di + 2] = (byte) (((dst[di + 2] & 0xFF) * a + (rgb & 0xFF) * inv) / 255);
                dst[di + 3] = (byte) 255;
            }
        }
    }
}
