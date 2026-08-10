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
    /**
     * A 0..1 signal as a 10-bit limited-range code.
     *
     * <p>Ten bits because both platforms now feed ten: Apple's
     * `420YpCbCr10BiPlanarVideoRange` and Android's `YCBCR_P010`. Limited range
     * puts black at 64 and white at 940, which is exactly the 8-bit 16 and 235
     * shifted up by two — so every expectation in this file that was written
     * against 8-bit codes still means the same thing.
     */
    private static int limitedCode(double signal) {
        return (int) Math.round(64.0 + signal * 876.0);
    }

    /** Neutral chroma, ten-bit. */
    private static final int NEUTRAL = 512;

    @Test
    public void limitedRangeMapsSixteenToBlackAndTwoThirtyFiveToWhite() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false);

        assertEquals(0x000000, color.toRgb(64, NEUTRAL, NEUTRAL));
        assertEquals(0xFFFFFF, color.toRgb(940, NEUTRAL, NEUTRAL));
    }

    @Test
    public void fullRangeMapsZeroToBlackAndTwoFiftyFiveToWhite() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, true);

        assertEquals(0x000000, color.toRgb(0, NEUTRAL, NEUTRAL));
        assertEquals(0xFFFFFF, color.toRgb(1023, NEUTRAL, NEUTRAL));
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

        // Fifteen, not the 8-bit test's sixteen. Limited-range black is code 64,
        // and read as full range that is 64/1023 of the scale — a hair under the
        // old 16/255 (64*255 = 16320 against 16*1023 = 16368) — so the flooring
        // decode lands one below. The point is unchanged: a visibly dark gray
        // where black was meant.
        assertEquals(15, red(asFull.toRgb(64, NEUTRAL, NEUTRAL)));
    }

    @Test
    public void theMatrixSeparatesSixOhOneFromSevenOhNine() {
        final ClipColor bt601 =
                new ClipColor(ClipColor.STANDARD_BT601, ClipColor.TRANSFER_SDR, false);
        final ClipColor bt709 =
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false);

        // A strongly colored sample, so the coefficients actually differ.
        final int a = bt601.toRgb(512, 360, 800);
        final int b = bt709.toRgb(512, 360, 800);

        assertTrue("601 and 709 must not agree on a saturated color", a != b);
        // Neutral is neutral under either, which is what keeps a grey card grey.
        assertEquals(bt601.toRgb(126, NEUTRAL, NEUTRAL), bt709.toRgb(126, NEUTRAL, NEUTRAL));
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

        assertEquals(0xFFFFFF, color.toRgb(940, NEUTRAL, NEUTRAL));
        assertEquals(0x000000, color.toRgb(64, NEUTRAL, NEUTRAL));
    }

    @Test
    public void bt2020NeutralGreyStaysNeutral() {
        final ClipColor color =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_SDR, false);

        // Within one code: the matrix is applied in single precision and the
        // sRGB table is 4096 entries deep, so an exact match would be asserting
        // on rounding rather than on neutrality.
        final int grey = color.toRgb(limitedCode(0.5), NEUTRAL, NEUTRAL);
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
    public void hlgDiffuseWhiteMatchesTheStandardsOwnAnswer() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);
        final ClipColor sdr =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_SDR, false);

        // Diffuse white in display light, normalized so 1.0 is the 1 000 cd/m2
        // BT.2446 assumes. Worked out here from BT.2100 rather than read back out
        // of the implementation: the HLG inverse OETF, then the OOTF at gamma 1.2.
        final double a = 0.17883277;
        final double b = 1.0 - 4.0 * a;
        final double c = 0.5 - a * Math.log(4.0 * a);
        final double scene = (Math.exp((0.75 - c) / a) + b) / 12.0;
        final double display = Math.pow(scene, 1.2);

        final int expected = methodAneutral(display);
        final int converted = red(hlg.toRgb(limitedCode(0.75), NEUTRAL, NEUTRAL));

        assertTrue("HLG diffuse white came out at " + converted + ", but Report ITU-R "
                        + "BT.2446-1 Method A puts it at " + expected,
                Math.abs(converted - expected) <= 2);

        // Still has to be a long way from ignoring the transfer, or the test
        // would pass with the HLG handling deleted.
        final int ignored = red(sdr.toRgb(limitedCode(0.75), NEUTRAL, NEUTRAL));
        assertTrue("treating HLG as SDR gave " + ignored + ", too close to " + converted,
                Math.abs(converted - ignored) >= 20);
    }

    /**
     * The tone map across its whole range, against the standard's own numbers.
     *
     * <p><b>All three branches, because one point tests one branch.</b> The
     * diffuse-white check above lands at {@code Yp' ≈ 0.77}, inside the
     * quadratic segment — so corrupting the linear segment's coefficient left it
     * green, and only the monotonicity test noticed. Found by deliberately
     * breaking the constant and watching which tests cared.
     */
    @Test
    public void theToneMapFollowsMethodAAcrossItsWholeRange() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);

        // Signals spanning deep shadow to peak, so every segment of the knee and
        // both of its joins are covered.
        final double[] signals = {0.10, 0.25, 0.40, 0.55, 0.65, 0.75, 0.85, 0.93, 0.98, 1.0};
        final double a = 0.17883277;
        final double b = 1.0 - 4.0 * a;
        final double c = 0.5 - a * Math.log(4.0 * a);

        for (final double signal : signals) {
            final double scene = signal <= 0.5
                    ? signal * signal / 3.0
                    : (Math.exp((signal - c) / a) + b) / 12.0;
            final int expected = methodAneutral(Math.pow(scene, 1.2));
            final int actual = red(hlg.toRgb(limitedCode(signal), NEUTRAL, NEUTRAL));
            assertTrue("signal " + signal + " converted to " + actual
                            + ", but Method A puts it at " + expected,
                    Math.abs(actual - expected) <= 2);
        }
    }

    /**
     * Method A applied to a neutral, worked out from the published constants.
     *
     * <p>Independent of {@link ClipColor} on purpose. A test that asked the
     * implementation what it produces and then asserted that number would pass
     * for any implementation, which is the failure that let the clamp survive.
     * The chroma terms vanish for a neutral, so this is the luma path alone.
     */
    private static int methodAneutral(double displayLinearAtPeak) {
        final double rho = 1.0 + 32.0 * Math.pow(1000.0 / 10000.0, 1.0 / 2.4);
        final double rhoSdr = 1.0 + 32.0 * Math.pow(100.0 / 10000.0, 1.0 / 2.4);

        final double yHdr = Math.pow(displayLinearAtPeak, 1.0 / 2.4);
        final double yp = Math.log(1.0 + (rho - 1.0) * yHdr) / Math.log(rho);
        final double yc;
        if (yp <= 0.7399) {
            yc = 1.0770 * yp;
        } else if (yp < 0.9909) {
            yc = -1.1510 * yp * yp + 2.7811 * yp - 0.6302;
        } else {
            yc = 0.5000 * yp + 0.5000;
        }
        final double ySdr = (Math.pow(rhoSdr, yc) - 1.0) / (rhoSdr - 1.0);

        // Back to linear, then the sRGB encode. A neutral survives the
        // BT.2020 to Rec.709 matrix unchanged because each row sums to one.
        final double linear = Math.pow(ySdr, 2.4);
        final double encoded = linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return (int) Math.round(255.0 * encoded);
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
            out[i] = red(hlg.toRgb(limitedCode(signals[i]), NEUTRAL, NEUTRAL));
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

        assertEquals(0x000000, hlg.toRgb(64, NEUTRAL, NEUTRAL));
    }

    @Test
    public void hlgRisesMonotonically() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);

        int previous = -1;
        // The whole of 10-bit limited range, black to white.
        for (int code = 64; code <= 940; code++) {
            final int value = red(hlg.toRgb(code, NEUTRAL, NEUTRAL));
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

        // 203 cd/m2 against the 1 000 cd/m2 peak BT.2446 assumes.
        final int expected = methodAneutral(203.0 / 1000.0);
        final int converted = red(pq.toRgb(limitedCode(signal), NEUTRAL, NEUTRAL));
        assertTrue("PQ reference white came out at " + converted + ", but Method A puts "
                        + "it at " + expected, Math.abs(converted - expected) <= 2);
    }

    /**
     * The interpolated table against the chain it stands in for.
     *
     * <p>The wide path answers from a 3-D table — exact per luma code,
     * bilinear across chroma, with the clamps evaluated per pixel after
     * interpolation and the kinked cells falling back to the chain itself.
     * This walks a lattice over the legal broadcast range and bounds the
     * disagreement: the worst measured is nine codes, at chroma past ninety
     * percent saturation, and the neutral axis — where every accuracy test
     * above lives — never strays past a single code of fixed-point rounding.
     * A change that coarsens the table, breaks the hot-cell marking, or
     * misindexes a node moves these numbers by tens, not fractions.
     */
    @Test
    public void theWideTableAgreesWithTheChain() {
        final ClipColor hlg =
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false);

        int worst = 0;
        long sum = 0;
        long count = 0;
        for (int y = 64; y <= 940; y += 13) {
            for (int u = 64; u <= 960; u += 11) {
                for (int v = 64; v <= 960; v += 11) {
                    final int a = hlg.toRgb(y, u, v);
                    final int b = hlg.wideChain(y, u, v);
                    for (int shift = 0; shift <= 16; shift += 8) {
                        final int d = Math.abs(((a >> shift) & 0xFF)
                                - ((b >> shift) & 0xFF));
                        sum += d;
                        count++;
                        if (d > worst) {
                            worst = d;
                        }
                    }
                }
            }
        }
        assertTrue("table strays " + worst + " codes from the chain", worst <= 9);
        assertTrue("mean disagreement " + (sum / (double) count),
                sum < count);

        for (int y = 64; y <= 940; y++) {
            final int a = hlg.toRgb(y, NEUTRAL, NEUTRAL);
            final int b = hlg.wideChain(y, NEUTRAL, NEUTRAL);
            for (int shift = 0; shift <= 16; shift += 8) {
                final int d = Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF));
                assertTrue("neutral luma " + y + " strays " + d + " codes", d <= 1);
            }
        }
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
