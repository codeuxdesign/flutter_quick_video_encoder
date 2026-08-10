package com.lib.flutter_quick_video_encoder;

/**
 * How a clip's YUV samples become the sRGB the rest of the frame is written in.
 *
 * <p>This is the piece Apple gets for free. On the iOS side one dictionary key —
 * {@code AVVideoColorPropertiesKey} — moves the whole conversion into the
 * decoder: ask for Rec.709 primaries, transfer and matrix and the buffer comes
 * back already tone mapped, on hardware, for no measurable cost. Android has no
 * equivalent. {@code MediaCodec} hands back whatever the file holds and tells
 * you what it is through {@code KEY_COLOR_STANDARD}, {@code KEY_COLOR_RANGE} and
 * {@code KEY_COLOR_TRANSFER}; converting it is the caller's job.
 *
 * <p><b>Left alone that is not a subtle error.</b> The corpus clip is BT.2020
 * with an HLG transfer. Decoded as if it were Rec.709 it is washed out and grey,
 * and it looks like a bug in the blend rather than a color-space problem — which
 * is the wrong thing to go looking at.
 *
 * <p><b>No android imports, deliberately.</b> Everything here is arithmetic on
 * numbers, so it runs in a plain JVM test on a laptop. That is the only part of
 * the Android clip path that can be tested without a phone, so it is worth
 * keeping the boundary sharp: {@link ClipReader} owns the codec, this owns the
 * math.
 *
 * <p><b>Highlights clip rather than roll off.</b> An HLG signal above diffuse
 * white has nowhere to go in an 8-bit sRGB frame, and the choice here is to
 * clamp — which is what AVFoundation does on the reference platform. A soft
 * shoulder would look better on a bright sky and would also make the Android
 * film differ from the macOS one for reasons unrelated to this work. If a tone
 * map is ever wanted it should be added to both platforms at once.
 */
final class ClipColor {

    // ---- what a file can say it is -----------------------------------------

    static final int STANDARD_BT601 = 1;
    static final int STANDARD_BT709 = 2;
    static final int STANDARD_BT2020 = 3;

    static final int TRANSFER_SDR = 1;
    static final int TRANSFER_HLG = 2;
    static final int TRANSFER_PQ = 3;

    /** Nominal peak of an HLG display, and the gamma that follows from it. */
    private static final double HLG_GAMMA = 1.2;

    /**
     * The signal level BT.2408 calls HLG reference white. Mapping it to sRGB
     * 1.0 is what stops a converted clip reading a stop darker than the map it
     * sits in.
     */
    private static final double HLG_DIFFUSE_WHITE_SIGNAL = 0.75;

    /** BT.2408's reference white in cd/m2, which is what PQ is normalized to. */
    private static final double PQ_REFERENCE_WHITE_NITS = 203.0;

    private static final int LINEAR_LUT = 1024;
    private static final int GAIN_LUT = 4096;
    private static final int ENCODE_LUT = 4096;

    final int standard;
    final int transfer;
    final boolean fullRange;

    /**
     * Whether the samples need the float pipeline rather than a matrix.
     *
     * <p>True for anything BT.2020 (the primaries differ, so a matrix alone
     * leaves the color wrong) and for anything whose transfer is not the SDR
     * one (the code values do not mean what sRGB thinks they mean). False for
     * ordinary Rec.709 or Rec.601 video, which is the common case and takes the
     * integer path.
     */
    final boolean wideGamutOrHdr;

    // Integer path, 16.16 fixed point, one table per input code.
    private final int[] yTerm = new int[256];
    private final int[] rFromV = new int[256];
    private final int[] gFromV = new int[256];
    private final int[] gFromU = new int[256];
    private final int[] bFromU = new int[256];

    // Float path. Signal space first, then linear light, then sRGB.
    private float[] wY;
    private float[] wRfromV;
    private float[] wGfromV;
    private float[] wGfromU;
    private float[] wBfromU;
    private float[] toLinear;
    private float[] ootfGain;
    private byte[] toSrgb;
    private float hlgScale;
    private float lumaR;
    private float lumaG;
    private float lumaB;

    ClipColor(int standard, int transfer, boolean fullRange) {
        this.standard = standard;
        this.transfer = transfer;
        this.fullRange = fullRange;
        this.wideGamutOrHdr = standard == STANDARD_BT2020 || transfer != TRANSFER_SDR;

        final double kr = standard == STANDARD_BT601 ? 0.299
                : standard == STANDARD_BT2020 ? 0.2627 : 0.2126;
        final double kb = standard == STANDARD_BT601 ? 0.114
                : standard == STANDARD_BT2020 ? 0.0593 : 0.0722;
        final double kg = 1.0 - kr - kb;

        // Limited range puts black at 16 and white at 235 for luma, and centers
        // chroma on 128 with 224 codes of swing. Full range uses all 256. Get
        // this wrong and the picture is merely low contrast, which reads as a
        // bad clip rather than as a bug.
        final double yScale = fullRange ? 1.0 / 255.0 : 1.0 / 219.0;
        final double yOffset = fullRange ? 0.0 : 16.0;
        final double cScale = fullRange ? 1.0 / 255.0 : 1.0 / 224.0;

        final double rv = 2.0 * (1.0 - kr);
        final double bu = 2.0 * (1.0 - kb);
        final double gv = -2.0 * (1.0 - kr) * kr / kg;
        final double gu = -2.0 * (1.0 - kb) * kb / kg;

        for (int i = 0; i < 256; i++) {
            final double y = (i - yOffset) * yScale;
            final double c = (i - 128) * cScale;
            yTerm[i] = (int) Math.round(y * 255.0 * 65536.0);
            rFromV[i] = (int) Math.round(c * rv * 255.0 * 65536.0);
            bFromU[i] = (int) Math.round(c * bu * 255.0 * 65536.0);
            gFromV[i] = (int) Math.round(c * gv * 255.0 * 65536.0);
            gFromU[i] = (int) Math.round(c * gu * 255.0 * 65536.0);
        }

        if (wideGamutOrHdr) {
            buildWidePath(kr, kg, kb, yScale, yOffset, cScale, rv, bu, gv, gu);
        }
    }

    private void buildWidePath(double kr, double kg, double kb,
                               double yScale, double yOffset, double cScale,
                               double rv, double bu, double gv, double gu) {
        lumaR = (float) kr;
        lumaG = (float) kg;
        lumaB = (float) kb;

        wY = new float[256];
        wRfromV = new float[256];
        wGfromV = new float[256];
        wGfromU = new float[256];
        wBfromU = new float[256];
        for (int i = 0; i < 256; i++) {
            final double y = (i - yOffset) * yScale;
            final double c = (i - 128) * cScale;
            wY[i] = (float) y;
            wRfromV[i] = (float) (c * rv);
            wBfromU[i] = (float) (c * bu);
            wGfromV[i] = (float) (c * gv);
            wGfromU[i] = (float) (c * gu);
        }

        toLinear = new float[LINEAR_LUT];
        for (int i = 0; i < LINEAR_LUT; i++) {
            toLinear[i] = (float) signalToLinear((double) i / (LINEAR_LUT - 1));
        }

        if (transfer == TRANSFER_HLG) {
            // The HLG opto-optical transfer function is a gain that depends on
            // the luminance of the whole pixel, not on each channel — which is
            // why it cannot be folded into the per-channel table above.
            ootfGain = new float[GAIN_LUT];
            for (int i = 0; i < GAIN_LUT; i++) {
                ootfGain[i] = (float) Math.pow((double) i / (GAIN_LUT - 1), HLG_GAMMA - 1.0);
            }
            // Derived rather than typed in. A neutral at the diffuse-white
            // signal has luminance equal to its own channel value, so its
            // display-linear level is the scale factor's reciprocal. It lands
            // near 0.203, which is BT.2408's 203 cd/m2 against a 1000 cd/m2
            // display — a useful sign that the chain above is right.
            final double diffuse = hlgSignalToScene(HLG_DIFFUSE_WHITE_SIGNAL);
            final double display = Math.pow(diffuse, HLG_GAMMA - 1.0) * diffuse;
            hlgScale = (float) (1.0 / display);
        }

        // Indexed by the square root of the linear value, so the entries crowd
        // together near black where sRGB is steepest. Indexing linearly would
        // quantize the shadows into visible bands.
        toSrgb = new byte[ENCODE_LUT];
        for (int i = 0; i < ENCODE_LUT; i++) {
            final double u = (double) i / (ENCODE_LUT - 1);
            toSrgb[i] = (byte) Math.round(255.0 * srgbEncode(u * u));
        }
    }

    /** One YUV triple as packed 0xRRGGBB. */
    int toRgb(int y, int u, int v) {
        if (!wideGamutOrHdr) {
            final int base = yTerm[y];
            final int r = clamp255((base + rFromV[v]) >> 16);
            final int g = clamp255((base + gFromU[u] + gFromV[v]) >> 16);
            final int b = clamp255((base + bFromU[u]) >> 16);
            return (r << 16) | (g << 8) | b;
        }
        return toRgbWide(y, u, v);
    }

    private int toRgbWide(int y, int u, int v) {
        final float base = wY[y];
        float r = clamp01(base + wRfromV[v]);
        float g = clamp01(base + wGfromU[u] + wGfromV[v]);
        float b = clamp01(base + wBfromU[u]);

        r = toLinear[(int) (r * (LINEAR_LUT - 1) + 0.5f)];
        g = toLinear[(int) (g * (LINEAR_LUT - 1) + 0.5f)];
        b = toLinear[(int) (b * (LINEAR_LUT - 1) + 0.5f)];

        if (transfer == TRANSFER_HLG) {
            float scene = lumaR * r + lumaG * g + lumaB * b;
            if (scene > 1.0f) {
                scene = 1.0f;
            } else if (scene < 0.0f) {
                scene = 0.0f;
            }
            final float gain = ootfGain[(int) (scene * (GAIN_LUT - 1) + 0.5f)] * hlgScale;
            r *= gain;
            g *= gain;
            b *= gain;
        }

        if (standard == STANDARD_BT2020) {
            final float lr = r;
            final float lg = g;
            final float lb = b;
            // Each row sums to one, which is what keeps white white and grey
            // neutral. A single mistyped coefficient tints the whole clip, and a
            // tint gets blamed on the camera rather than on the conversion.
            r = 1.660491f * lr - 0.587641f * lg - 0.072850f * lb;
            g = -0.124550f * lr + 1.132900f * lg - 0.008350f * lb;
            b = -0.018151f * lr - 0.100579f * lg + 1.118730f * lb;
        }

        return (srgbByte(r) << 16) | (srgbByte(g) << 8) | srgbByte(b);
    }

    private int srgbByte(float linear) {
        if (linear <= 0.0f) {
            return 0;
        }
        if (linear >= 1.0f) {
            return 255;
        }
        final int index = (int) (Math.sqrt(linear) * (ENCODE_LUT - 1) + 0.5f);
        return toSrgb[index] & 0xFF;
    }

    private double signalToLinear(double signal) {
        switch (transfer) {
            case TRANSFER_HLG:
                return hlgSignalToScene(signal);
            case TRANSFER_PQ:
                return pqSignalToNits(signal) / PQ_REFERENCE_WHITE_NITS;
            default:
                // The Rec.709 opto-electronic transfer function, inverted. This
                // only runs for BT.2020 content with an SDR transfer, which
                // still needs linear light to cross into Rec.709 primaries.
                if (signal < 0.081) {
                    return signal / 4.5;
                }
                return Math.pow((signal + 0.099) / 1.099, 1.0 / 0.45);
        }
    }

    /** BT.2100's HLG signal to scene light, both normalized to 0..1. */
    private static double hlgSignalToScene(double signal) {
        final double a = 0.17883277;
        final double b = 1.0 - 4.0 * a;
        final double c = 0.5 - a * Math.log(4.0 * a);
        if (signal <= 0.5) {
            return signal * signal / 3.0;
        }
        return (Math.exp((signal - c) / a) + b) / 12.0;
    }

    /** SMPTE ST 2084 signal to absolute luminance in cd/m2. */
    private static double pqSignalToNits(double signal) {
        final double m1 = 2610.0 / 16384.0;
        final double m2 = 2523.0 / 4096.0 * 128.0;
        final double c1 = 3424.0 / 4096.0;
        final double c2 = 2413.0 / 4096.0 * 32.0;
        final double c3 = 2392.0 / 4096.0 * 32.0;
        final double e = Math.pow(Math.max(signal, 0.0), 1.0 / m2);
        final double num = Math.max(e - c1, 0.0);
        final double den = c2 - c3 * e;
        if (den <= 0.0) {
            return 10000.0;
        }
        return 10000.0 * Math.pow(num / den, 1.0 / m1);
    }

    private static double srgbEncode(double linear) {
        if (linear <= 0.0031308) {
            return 12.92 * linear;
        }
        return 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
    }

    private static float clamp01(float v) {
        if (v < 0.0f) {
            return 0.0f;
        }
        return v > 1.0f ? 1.0f : v;
    }

    private static int clamp255(int v) {
        if (v < 0) {
            return 0;
        }
        return v > 255 ? 255 : v;
    }

    /**
     * What this run is actually converting, for the log.
     *
     * <p>Printed for every clip that opens, because a color decision that was
     * inferred from a missing key is exactly the kind of thing that has to be
     * visible in the output rather than reconstructed from how the film looked.
     */
    String describe() {
        final String s = standard == STANDARD_BT601 ? "BT.601"
                : standard == STANDARD_BT2020 ? "BT.2020" : "BT.709";
        final String t = transfer == TRANSFER_HLG ? "HLG"
                : transfer == TRANSFER_PQ ? "PQ" : "SDR";
        return s + "/" + t + "/" + (fullRange ? "full" : "limited");
    }
}
