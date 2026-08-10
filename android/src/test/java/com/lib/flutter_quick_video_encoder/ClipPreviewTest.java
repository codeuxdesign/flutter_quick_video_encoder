package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * The downsample and the conversion behind a trim handle's preview.
 *
 * <p>The one assertion that carries the feature is
 * {@link #aPreviewIsTheCompositeAtPreviewSize()}: a preview exists to promise
 * what the export will draw, so the interesting question is not whether it looks
 * like a picture — a wrong one would — but whether it is byte for byte the
 * picture {@link ClipBlend} composites from the same frame. Everything else here
 * is the geometry around that.
 *
 * <p>The codec half cannot be tested without a phone and is covered by
 * {@code ClipReaderTest} on hardware. This half is where the arithmetic lives.
 */
public class ClipPreviewTest {

    /** An 8-bit code as its full-range 10-bit equivalent; see `ClipBlendTest`. */
    private static short wide(int eightBit) {
        return (short) ((eightBit * 1023 + 254) / 255);
    }

    /**
     * A frame whose luma walks a diagonal ramp, so no two rows and no two columns
     * are alike.
     *
     * <p>Flat gray would pass a preview that sampled the same pixel everywhere,
     * which is exactly the bug a downsample can have — and it would pass one that
     * transposed the image, too.
     */
    private static ClipFrame rampFrame(int width, int height) {
        final short[] luma = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                luma[y * width + x] = wide((x * 7 + y * 13) % 256);
            }
        }
        final int cw = (width + 1) / 2;
        final int ch = (height + 1) / 2;
        final short[] cb = new short[cw * ch];
        final short[] cr = new short[cw * ch];
        for (int i = 0; i < cb.length; i++) {
            // Off-center on purpose. Neutral chroma would let a preview that
            // dropped the conversion and copied luma into all three channels
            // pass; pushed chroma means red, green and blue disagree, so the
            // comparison against the composite is testing the conversion too.
            cb[i] = wide(100 + (i % 40));
            cr[i] = wide(180 - (i % 40));
        }
        return new ClipFrame(luma, cb, cr, width, height,
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true), 0L,
                false);
    }

    /**
     * The preview and the composite have to be the same pixels.
     *
     * <p>{@code ClipBlend.blend} into a fully transparent destination reduces to
     * the clip alone, so compositing the same frame into a rectangle the size of
     * the preview is the export's answer to the same question. If these ever
     * diverge the rider is trimming against something the film will not show,
     * which is the whole failure this API was added to avoid — and it would be
     * invisible, because both images look like the clip.
     */
    @Test
    public void aPreviewIsTheCompositeAtPreviewSize() {
        final ClipFrame frame = rampFrame(40, 24);

        final ClipPreview.Image preview = ClipPreview.from(frame, 10);
        assertEquals(10, preview.width);
        assertEquals(6, preview.height);

        final byte[] composited = new byte[preview.width * preview.height * 4];
        ClipBlend.blend(composited, preview.width, preview.height, frame,
                0, 0, preview.width, preview.height, 0);

        assertArrayEquals("the preview must be the composite, pixel for pixel",
                composited, preview.rgba);
    }

    /** And the same at a size that divides evenly, in case rounding was hiding it. */
    @Test
    public void aPreviewIsTheCompositeAtAnExactDivision() {
        final ClipFrame frame = rampFrame(32, 16);
        final ClipPreview.Image preview = ClipPreview.from(frame, 8);

        final byte[] composited = new byte[preview.width * preview.height * 4];
        ClipBlend.blend(composited, preview.width, preview.height, frame,
                0, 0, preview.width, preview.height, 0);

        assertEquals(8, preview.width);
        assertEquals(4, preview.height);
        assertArrayEquals(composited, preview.rgba);
    }

    @Test
    public void theLongerSideIsCappedAndTheAspectSurvives() {
        final ClipPreview.Image landscape = ClipPreview.from(rampFrame(3840, 2160), 512);
        assertEquals(512, landscape.width);
        assertEquals(288, landscape.height);

        // Portrait footage caps its height instead, which is the case a preview
        // written against 16:9 gets wrong by handing back a stretched frame.
        final ClipPreview.Image portrait = ClipPreview.from(rampFrame(1080, 1920), 512);
        assertEquals(512, portrait.height);
        assertEquals(288, portrait.width);
    }

    @Test
    public void aFrameAlreadyInsideTheCapKeepsItsOwnSize() {
        final ClipPreview.Image image = ClipPreview.from(rampFrame(320, 180), 512);

        assertEquals(320, image.width);
        assertEquals(180, image.height);
        assertEquals(320 * 180 * 4, image.rgba.length);
    }

    /**
     * The dimensions come off the frame, not off the request.
     *
     * <p>A decoded frame carries a crop rectangle, so what the decoder hands back
     * is not always what the container advertised — and the caller lays the bytes
     * out by these numbers. Reporting the request instead would shear the
     * picture, which reads as a corrupt file rather than as a wrong integer.
     */
    @Test
    public void theReportedSizeIsTheSizeOfTheBuffer() {
        for (final int edge : new int[]{7, 64, 513}) {
            final ClipPreview.Image image = ClipPreview.from(rampFrame(97, 61), edge);
            assertEquals("rgba length at maxEdge " + edge,
                    image.width * image.height * 4, image.rgba.length);
            assertEquals("longer side at maxEdge " + edge,
                    Math.min(edge, 97), Math.max(image.width, image.height));
        }
    }

    /**
     * An extreme aspect must not scale its short edge to nothing.
     *
     * <p>A zero would come back as an empty array with a plausible-looking width,
     * and `decodeImageFromPixels` would then be handed a buffer that cannot
     * describe the image it was told about.
     */
    @Test
    public void theShortEdgeNeverCollapses() {
        final ClipPreview.Image image = ClipPreview.from(rampFrame(4000, 2), 16);

        assertEquals(16, image.width);
        assertEquals(1, image.height);
        assertEquals(16 * 1 * 4, image.rgba.length);
    }

    @Test
    public void everyPixelIsOpaque() {
        final ClipPreview.Image image = ClipPreview.from(rampFrame(40, 24), 10);

        for (int i = 0; i < image.width * image.height; i++) {
            assertEquals("alpha at " + i, 255, image.rgba[i * 4 + 3] & 0xFF);
        }
    }

    /**
     * Two different frames must not produce the same bytes.
     *
     * <p>Cheap, and it is the shape of the failure this whole feature ships with
     * if the seek is ignored somewhere below: a reader that answers every scrub
     * position with the same keyframe produces a perfectly plausible preview. The
     * decode side of that is asserted on hardware; this asserts that the
     * conversion is not itself flattening two frames into one picture.
     */
    @Test
    public void differentFramesConvertToDifferentPixels() {
        final ClipFrame first = rampFrame(40, 24);
        final short[] shifted = new short[first.luma.length];
        for (int i = 0; i < shifted.length; i++) {
            shifted[i] = wide((((first.luma[i] & 0x3FF) * 255 / 1023) + 90) % 256);
        }
        final ClipFrame second = new ClipFrame(shifted, first.cb, first.cr, 40, 24,
                first.color, 1_000_000L, false);

        assertTrue("a changed frame must change the preview",
                !Arrays.equals(ClipPreview.from(first, 10).rgba,
                        ClipPreview.from(second, 10).rgba));
    }

    @Test
    public void nothingUsableAnswersWithNull() {
        assertNull(ClipPreview.from(null, 512));
        assertNull(ClipPreview.from(rampFrame(40, 24), 0));
        assertNull(ClipPreview.from(rampFrame(40, 24), -1));
    }
}
