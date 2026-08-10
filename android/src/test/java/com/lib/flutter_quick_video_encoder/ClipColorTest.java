package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reading a clip's samples as the sRGB the rest of the frame is written in.
 *
 * <p>This is the part of the Android clip path with no Apple counterpart to copy
 * — one dictionary key does all of it there — so it is also the part with
 * nothing but these assertions standing behind it. The HDR cases are written as
 * differences rather than absolutes: what makes them informative is that
 * ignoring the transfer function produces a specific, much lower number, and the
 * test names it.
 */
public class ClipColorTest {

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /** The luma code carrying [signal] of the range, 0..1. */
    private static int limitedCode(double signal) {
        return (int) Math.round(16.0 + signal * 219.0);
    }

    @Test
    public void limitedRangeMapsSixteenToBlackAndTwoThirtyFiveToWhite() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false);

        assertEquals(0x000000, color.toRgb(16, 128, 128));
        assertEquals(0xFFFFFF, color.toRgb(235, 128, 128));
    }

    @Test
    public void fullRangeMapsZeroToBlackAndTwoFiftyFiveToWhite() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true);

        assertEquals(0x000000, color.toRgb(0, 128, 128));
        assertEquals(0xFFFFFF, color.toRgb(255, 128, 128));
    }

    /**
     * Reading limited-range video as full range is the classic quiet mistake: the
     * picture is merely low contrast and reads as a flat clip rather than as a
     * bug. Black at code 16 under the wrong assumption is a visible dark grey.
     */
    @Test
    public void theRangeAssumptionChangesBlack() {
        final ClipColor asFull =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true);

        assertEquals(16, red(asFull.toRgb(16, 128, 128)));
    }

    @Test
    public void theMatrixSeparatesSixOhOneFromSevenOhNine() {
        final ClipColor bt601 =
                new ClipColor(ClipColor.STANDARD_BT601, ClipColor.TRANSFER_SDR, false);
        final ClipColor bt709 =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false);

        // A strongly colored sample, so the coefficients actually differ.
        final int a = bt601.toRgb(128, 90, 200);
        final int b = bt709.toRgb(128, 90, 200);

        assertTrue("601 and 709 must not agree on a saturated color", a != b);
        // Neutral is neutral under either, which is what keeps a grey card grey.
        assertEquals(bt601.toRgb(126, 128, 128), bt709.toRgb(126, 128, 128));
    }

    /**
     * Every row of the BT.2020 to Rec.709 matrix sums to one, so white in is
     * white out. A single mistyped coefficient tints the whole clip, and a tint
     * is exactly the kind of wrongness that gets blamed on the camera.
     */
    @Test
    public void bt2020WhiteStaysWhite() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_SDR, false);

        assertEquals(0xFFFFFF, color.toRgb(235, 128, 128));
        assertEquals(0x000000, color.toRgb(16, 128, 128));
    }

    @Test
    public void bt2020NeutralGreyStaysNeutral() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_SDR, false);

        // Within one code: the matrix is applied in single precision and the
        // sRGB table is 4096 entries deep, so an exact match would be asserting
        // on rounding rather than on neutrality.
        final int grey = color.toRgb(limitedCode(0.5), 128, 128);
        assertTrue("red " + red(grey) + " and green " + green(grey),
                Math.abs(red(grey) - green(grey)) <= 1);
        assertTrue("green " + green(grey) + " and blue " + blue(grey),
                Math.abs(green(grey) - blue(grey)) <= 1);
    }

    /**
     * The one the whole HDR path exists for.
     *
     * <p>BT.2408 puts HLG diffuse white at 75% of the signal range. It has to
     * come out bright — that is what stops a drone clip reading a stop darker and
     * greyer than the map it is composited into, which is how this failed on
     * Apple before `AVVideoColorPropertiesKey` was added.
     *
     * <p><b>It must not come out at 255.</b> An HLG signal reaches about seven
     * and a half times diffuse white, so putting white at the ceiling leaves
     * nowhere for any of that to go and every highlight becomes the same flat
     * shape. Reserving headroom is what {@link #hlgHighlightsAboveWhiteStaySeparate}
     * then has something to measure.
     *
     * <p>The lower bound is what makes it informative: decoding the same code as
     * an ordinary SDR transfer gives roughly 198, so a test that only checked
     * "bright" would pass with the HLG handling deleted.
     */
    @Test
    public void hlgDiffuseWhiteIsBrightButLeavesHeadroom() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);
        final ClipColor sdr =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_SDR, false);

        final int code = limitedCode(0.75);
        final int converted = red(hlg.toRgb(code, 128, 128));
        final int ignored = red(sdr.toRgb(code, 128, 128));

        assertTrue("HLG diffuse white came out at " + converted + ", not bright enough",
                converted >= 200);
        assertTrue("HLG diffuse white came out at " + converted + ", which is at or near "
                        + "the ceiling — nothing above white can be shown from there",
                converted <= 245);
        assertTrue("treating HLG as SDR gave " + ignored + ", which is not far enough"
                        + " from " + converted + " for this test to mean anything",
                Math.abs(converted - ignored) >= 20);
    }

    /**
     * The property that separates a roll-off from a clamp, and the one nothing
     * here stated for as long as this clamped.
     *
     * <p>Diffuse white, and three signals above it, have to arrive as four
     * *different* values. Under the clamp they were four identical 255s: a sun
     * and the sky behind it rendered as one shape, and the film differed from the
     * macOS one for the same footage.
     *
     * <p>Nothing already in this file could fail that way. Diffuse white near
     * white, black staying black and a monotonically rising curve are all true of
     * a clamping implementation — true, and insufficient. That is why this test
     * asserts separation rather than brightness.
     */
    @Test
    public void hlgHighlightsAboveWhiteStaySeparate() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);

        final double[] signals = {0.75, 0.85, 0.93, 1.0};
        final int[] out = new int[signals.length];
        for (int i = 0; i < signals.length; i++) {
            out[i] = red(hlg.toRgb(limitedCode(signals[i]), 128, 128));
        }

        for (int i = 1; i < out.length; i++) {
            assertTrue("signal " + signals[i] + " gave " + out[i] + ", the same as or below "
                            + signals[i - 1] + " at " + out[i - 1]
                            + " — highlights above diffuse white are being crushed together",
                    out[i] > out[i - 1]);
        }

        assertTrue("the whole range above diffuse white spans only "
                        + (out[out.length - 1] - out[0]) + " codes, which is not enough to "
                        + "tell a sun from the sky behind it",
                out[out.length - 1] - out[0] >= 8);
    }

    @Test
    public void hlgBlackIsStillBlack() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);

        assertEquals(0x000000, hlg.toRgb(16, 128, 128));
    }

    @Test
    public void hlgRisesMonotonically() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);

        int previous = -1;
        for (int code = 16; code <= 235; code++) {
            final int value = red(hlg.toRgb(code, 128, 128));
            assertTrue("code " + code + " went backwards: " + value + " after " + previous,
                    value >= previous);
            previous = value;
        }
        assertEquals("the top of the range must reach white", 255, previous);
    }

    /**
     * PQ is normalized to BT.2408's 203 cd/m2 reference white, so the code that
     * carries 203 nits has to come out bright for the same reason HLG's diffuse
     * white does — and, for the same reason, must stop short of the ceiling.
     * PQ runs far above reference white too, so putting 203 nits at 255 would
     * clip every specular highlight a PQ file has. Nothing in the corpus is PQ;
     * this is here so the first file that is comes out neither four stops too
     * dark nor entirely white above the middle.
     */
    @Test
    public void pqReferenceWhiteIsBrightButLeavesHeadroom() {
        final ClipColor pq =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_PQ, false);

        // ST 2084's inverse EOTF at 203 cd/m2, worked out independently of the
        // implementation rather than read back out of it.
        final double m1 = 2610.0 / 16384.0;
        final double m2 = 2523.0 / 4096.0 * 128.0;
        final double c1 = 3424.0 / 4096.0;
        final double c2 = 2413.0 / 4096.0 * 32.0;
        final double c3 = 2392.0 / 4096.0 * 32.0;
        final double y = Math.pow(203.0 / 10000.0, m1);
        final double signal = Math.pow((c1 + c2 * y) / (1.0 + c3 * y), m2);

        final int converted = red(pq.toRgb(limitedCode(signal), 128, 128));
        assertTrue("PQ reference white came out at " + converted + ", too dark",
                converted >= 200);
        assertTrue("PQ reference white came out at " + converted + ", at the ceiling — "
                        + "every specular highlight above it would clip to the same value",
                converted <= 245);
    }

    @Test
    public void anSdrRec709ClipTakesTheIntegerPath() {
        assertTrue(new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false)
                .wideGamutOrHdr == false);
        assertTrue(new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_SDR, false)
                .wideGamutOrHdr);
        assertTrue(new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_HLG, false)
                .wideGamutOrHdr);
    }

    @Test
    public void describeNamesWhatWasAssumed() {
        assertEquals("BT.2020/HLG/limited",
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false)
                        .describe());
        assertEquals("BT.709/SDR/full",
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true)
                        .describe());
    }
}
