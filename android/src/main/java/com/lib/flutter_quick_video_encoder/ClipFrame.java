package com.lib.flutter_quick_video_encoder;

/**
 * One decoded clip frame, copied out of the codec and owned by us.
 *
 * <p><b>Why a copy rather than the codec's own buffer.</b> The Apple side holds
 * a {@code CVPixelBufferRef} and reads it in place, which costs nothing. The
 * Android equivalent would be holding the {@code Image} and leaving the output
 * buffer un-released for as long as the frame is on screen — and an output
 * buffer that is never returned is one the decoder cannot reuse. A decoder with
 * a small pool then stops producing, {@code dequeueOutputBuffer} returns
 * {@code INFO_TRY_AGAIN_LATER} forever, and an export that cannot be tested on
 * a device would wedge on someone else's phone. Copying costs about 12 MB and a
 * memcpy per frame the film actually holds, and it cannot deadlock.
 *
 * <p><b>Planes are packed on the way in, not read with strides on the way out.</b>
 * {@code COLOR_FormatYUV420Flexible} is a family: one device hands back planar
 * I420, the next semiplanar NV12 with a chroma pixel stride of 2, and both come
 * with row strides that are not the width. That variation is dealt with exactly
 * once, in {@link ClipReader}, and everything downstream sees tightly packed
 * planes. This is the classic Android video bug and it is invisible on whichever
 * phone the code was written on.
 *
 * <p>No android imports, so the sampling and the blend on top of it can be
 * tested in a plain JVM.
 */
final class ClipFrame {

    /**
     * Ten-bit samples, one per array slot.
     *
     * <p><b>`short` rather than `byte`, which doubles the frame and is the
     * point.</b> A 4K frame goes from about 12 MB to 25 MB. What it buys is that
     * the tone map's curve is evaluated on ten bits and quantised once
     * afterwards, instead of quantising to eight first and then bending a steep
     * curve through the result — which is where a smooth sky bands. Nothing
     * downstream widens: `rgbAt` still returns packed 8-bit RGB and `ClipBlend`
     * is untouched.
     *
     * <p>Signed, because Java has no unsigned short; every read masks with
     * `0x3FF`, which is also what keeps an 8-bit source shifted up by two
     * indistinguishable from a native ten-bit one.
     */
    final short[] luma;
    final short[] cb;
    final short[] cr;
    final int width;
    final int height;
    final int chromaWidth;
    final int chromaHeight;
    final ClipColor color;
    final long presentationTimeUs;

    /**
     * Whether the decoder handed this frame back as `YCBCR_P010` rather than
     * `YUV_420_888`.
     *
     * <p><b>Carried so a test can assert which branch ran, not just that the
     * pixels look plausible.</b> A 10-bit source does not guarantee a 10-bit
     * image: ask for `COLOR_FormatYUV420Flexible` and a decoder is free to hand
     * back 8-bit, having tone-mapped or truncated on the way. The frame then
     * decodes, converts and composites perfectly well, and the P010 path this
     * flag names never executes — so a test checking only the colors passes
     * while covering nothing. Bit depth is not observable from the output; it
     * has to be reported by the thing that saw it.
     */
    final boolean tenBit;

    /**
     * How far a luma coordinate shifts right to index chroma: 1 for a 4:2:0
     * frame, 0 for one whose chroma is already per-pixel.
     *
     * <p><b>A field rather than the literal `>> 1` it replaces, and the preview
     * path is why.</b> A preview samples a handful of the source's pixels — 320
     * of 3840 across — and the chroma each of those wants is at
     * `sourceX >> 1`, which for adjacent output pixels is six samples apart.
     * A half-size chroma plane cannot hold that: two neighbours would collapse
     * onto one entry and the picture would differ from the export's. So a
     * sampled frame carries one chroma sample per pixel and shifts by nothing,
     * while a decoded frame is 4:2:0 and shifts by one. Both then read through
     * the same [rgbAt].
     */
    final int chromaShift;

    ClipFrame(short[] luma, short[] cb, short[] cr, int width, int height,
              ClipColor color, long presentationTimeUs, boolean tenBit) {
        this(luma, cb, cr, width, height, (width + 1) / 2, (height + 1) / 2, 1,
                color, presentationTimeUs, tenBit);
    }

    ClipFrame(short[] luma, short[] cb, short[] cr, int width, int height,
              int chromaWidth, int chromaHeight, int chromaShift,
              ClipColor color, long presentationTimeUs, boolean tenBit) {
        this.luma = luma;
        this.cb = cb;
        this.cr = cr;
        this.width = width;
        this.height = height;
        this.chromaWidth = chromaWidth;
        this.chromaHeight = chromaHeight;
        this.chromaShift = chromaShift;
        this.color = color;
        this.presentationTimeUs = presentationTimeUs;
        this.tenBit = tenBit;
    }

    /**
     * An 8-bit sample as a ten-bit code, by the file's own range.
     *
     * <p>**Moved here from `ClipReader` so the sampler can share it**, and
     * because widening to the ten bits this class stores is this class's
     * business. `ClipReader` still calls it under its own name.
     *
     * <p>A limited-range file shifts; a full-range one scales. Shifting a
     * full-range sample would leave white at 1020 of 1023, so every non-zero
     * sample decodes one low and white comes back 254 — a uniform darkening
     * with nothing logged, and the corpus has full-range files in it.
     *
     * <p>The full-range form is a ceiling rather than a round, because
     * {@link ClipColor}'s integer path floors on the way back to 8 bits. The
     * widened code has to sit at or above the exact {@code v * 1023 / 255} to
     * survive that floor; rounding puts half the values a fraction below it.
     */
    static short widen(int eightBit, boolean fullRange) {
        return (short) (fullRange ? (eightBit * 1023 + 254) / 255 : eightBit << 2);
    }

    /**
     * The pixel at [sx],[sy] as packed 0xRRGGBB.
     *
     * <p>The conversion happens here, per sampled pixel, rather than over the
     * whole frame when it is decoded. A 4K source is 8.3 megapixels and the
     * rectangle it is drawn into is at most a quarter of that, so converting
     * eagerly would do four times the work — and every frame the reader skips
     * past would pay for a conversion nobody looks at.
     */
    int rgbAt(int sx, int sy) {
        final int y = luma[sy * width + sx] & 0x3FF;
        final int ci = (sy >> chromaShift) * chromaWidth + (sx >> chromaShift);
        return color.toRgb(y, cb[ci] & 0x3FF, cr[ci] & 0x3FF);
    }
}
