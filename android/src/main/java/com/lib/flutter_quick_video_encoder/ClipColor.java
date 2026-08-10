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
 * <p><b>Highlights roll off rather than clip, and the previous note here was
 * wrong about why.</b> It said clamping "is what AVFoundation does on the
 * reference platform". Nobody had put a frame beside it. AVFoundation rolls off:
 * converting one 4K HLG drone frame both ways gave mean RGB 66/69/69 for
 * AVFoundation against 92/97/92 here, and the average understated it — Apple
 * kept the sun as a disc against a blue sky while this clamped both to one flat
 * white shape.
 *
 * <p>Worth keeping as a caution about the tests, not just the code. The HLG
 * cases assert that diffuse white lands near white, that black stays black and
 * that the curve rises monotonically. All three are true of a clamping
 * implementation. There was no missing assertion — the property that separates
 * rolled-off from clipped was simply never stated, and only two frames side by
 * side surfaced it.
 */
final class ClipColor {

    // ---- what a file can say it is -----------------------------------------

    static final int STANDARD_BT601 = 1;
    static final int STANDARD_BT709 = 2;
    static final int STANDARD_BT2020 = 3;

    static final int TRANSFER_SDR = 1;
    static final int TRANSFER_HLG = 2;
    static final int TRANSFER_PQ = 3;

    /**
     * Where the converted picture is going.
     *
     * <p>**A parameter rather than a hardcoded return type**, because it is the
     * one decision that is cheap to make now and a refactor to make later. Only
     * {@link #OUTPUT_SDR_REC709} is implemented; the HLG constant exists so the
     * seam is real and so an unimplemented path fails loudly instead of quietly
     * producing SDR pixels under an HDR label.
     */
    static final int OUTPUT_SDR_REC709 = 1;
    static final int OUTPUT_HLG_BT2020 = 2;

    /** Nominal peak of an HLG display, and the gamma that follows from it. */
    private static final double HLG_GAMMA = 1.2;

    /**
     * Peak luminances the tone map is specified against, in cd/m2.
     *
     * <p>Report ITU-R BT.2446-1 §4.1 maps a 1 000 cd/m2 HDR signal to a 100
     * cd/m2 SDR display. HLG is nominally mastered at 1 000, so the assumption
     * holds for the footage this reads.
     */
    private static final double TONE_MAP_HDR_NITS = 1000.0;
    private static final double TONE_MAP_SDR_NITS = 100.0;

    /** How finely the tone map and the two gamma curves are sampled. */
    private static final int TONE_LUT = 4096;

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

    /**
     * The sRGB encode is two tables, not one, and the split is a measurement.
     *
     * <p>One table indexed by the square root of the linear value crowds its
     * entries near black where sRGB is steepest, which is correct and costs a
     * {@code Math.sqrt} per channel — three per pixel, two million pixels a
     * frame. Measured on a Galaxy S24 Ultra compositing a 4K clip into
     * 1080x1920, that and the float traffic around it came to 400 ms a frame on
     * top of the decode.
     *
     * <p>Two linearly indexed tables get the same precision for a compare and a
     * multiply: a fine one over the shadows, where a step of one part in
     * a hundred thousand keeps quantization under a thirtieth of a code, and a
     * coarse one over everything above.
     */
    private static final float ENCODE_SPLIT = 0.02f;
    private static final int ENCODE_LOW_LUT = 2048;
    private static final int ENCODE_HIGH_LUT = 4096;

    final int standard;
    final int transfer;
    final boolean fullRange;
    final int outputSpace;

    /**
     * Whether anything can exceed diffuse white, and so whether a roll-off runs.
     *
     * <p>False for an SDR source, where the signal cannot go above white and a
     * roll-off would only darken a picture that was already correct.
     */
    private final boolean compressHighlights;

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

    /**
     * How many input codes the tables cover.
     *
     * <p><b>Ten bits on both platforms, and an 8-bit source is shifted up.</b>
     * Apple hands back `420YpCbCr10BiPlanarVideoRange` and Android
     * `YCBCR_P010`, so ten is the wider of the two and the one that loses
     * nothing. The shift is exact at both ends of limited range — 16 becomes 64
     * and 235 becomes 940 — so an 8-bit clip converts identically to before.
     */
    static final int CODES = 1024;

    /**
     * The wide path split in two: an interpolated core and an exact epilogue.
     *
     * <p><b>Why a table rather than a faster chain.</b> The chain behind
     * {@link #wideChain} is fourteen 1-D lookups and some forty float
     * operations per pixel, and none of them is individually wasteful — the
     * plane copy was rewritten and moved nothing, and shaving constants here
     * was headed the same way. What is wasteful is running the whole of it two
     * million times a frame when its three inputs are ten-bit codes: most of
     * the function fits in a table built once per clip.
     *
     * <p><b>Most, not all, because clamps are not smooth.</b> The first design
     * tabulated the finished sRGB bytes, and on a real drone frame it missed
     * by up to 22 codes with a mean of 2.9: wherever a channel saturates —
     * and at HLG's bright end most cells hold a saturation boundary, because
     * the inverse OETF is an exponential and one 64-code chroma cell spans
     * two and a half times of linear light — the finished output has a slope
     * kink, and a straight line across a kink overshoots. No affordable grid
     * fixes that: the worst kink slope measured ~6 output codes per input
     * code, which would need cells a single code wide.
     *
     * <p>So the table stores the chain's <em>smooth core</em> instead: the
     * unclamped tone-domain triple (the report's R', G', B' after Table 3),
     * which no clamp has touched yet. Every clamp lives in the epilogue —
     * clamp to [0,1], the 2.4 gamma decode, the BT.2020 to Rec.709 matrix,
     * the sRGB encode — and the epilogue runs per pixel, exactly: it is the
     * chain's own tail, reading the chain's own tables, entered through
     * integer index math. A clamp applied after interpolation cannot kink
     * the interpolant.
     * For a wide-gamut source with an SDR transfer there is no tone mapping;
     * its core is the same triple in the same 1/2.4 domain, taken just before
     * the matrix, so one epilogue serves both.
     *
     * <p><b>Exact per luma code, interpolated across chroma.</b> Luma carries
     * the nonlinearity — the transfer function, the OOTF, the tone map's knee
     * — and it is the axis the accuracy tests walk code by code, so it gets
     * all 1024 rows and no interpolation error. Chroma is interpolated
     * bilinearly between nodes every 64 codes. Neutral chroma, 512, lands
     * exactly on a node, which keeps a gray card as close to the chain as the
     * epilogue's fixed point can carry it — within a code.
     *
     * <p>1024 x 17 x 17 nodes of three shorts is 1.8 MB per wide-gamut clip —
     * beside the 25 MB the decoded frame itself holds, and freed with the
     * reader.
     */
    private static final int CHROMA_CELL_SHIFT = 6;
    private static final int CHROMA_CELL_MASK = (1 << CHROMA_CELL_SHIFT) - 1;
    private static final int CHROMA_NODES = (CODES >> CHROMA_CELL_SHIFT) + 1;

    /**
     * The core's fixed-point scale: tone values in twelve fractional bits.
     *
     * <p>Twelve because the output byte moves at most ~280 codes per tone
     * unit, so a step of 1/4096 is under a tenth of a code — and because a
     * node has to fit a short with headroom, the unclamped tone triple having
     * no ceiling of its own.
     *
     * <p>The epilogue deliberately builds no tables of its own: it reads the
     * chain's {@code gammaDecode24}, multiplies by the chain's matrix
     * literals and encodes through the chain's {@code srgbByte}. The first
     * version had an all-integer epilogue with freshly built tables, and it
     * cost cross-platform agreement: {@code Math.pow} here and {@code pow} in
     * libm differ by an ulp on some inputs, and an integer table bakes that
     * ulp into a full output code at every entry it flips — measured at three
     * percent of a dense lattice disagreeing with the Apple port, against 22
     * points in 2.7 million for the float chain. Reusing the chain's own
     * tables keeps the new path inside the divergence class the platforms
     * already had.
     */
    private static final int TONE_ONE = 4096;

    /** 10-bit limited range: luma 64..940, chroma centred on 512 with 896 of swing. */
    private static final double CODE_MAX = 1023.0;
    private static final double LIMITED_LUMA_OFFSET = 64.0;
    private static final double LIMITED_LUMA_SPAN = 876.0;
    private static final double CHROMA_CENTRE = 512.0;
    private static final double LIMITED_CHROMA_SPAN = 896.0;

    // Integer path, 16.16 fixed point, one table per input code.
    private final int[] yTerm = new int[CODES];
    private final int[] rFromV = new int[CODES];
    private final int[] gFromV = new int[CODES];
    private final int[] gFromU = new int[CODES];
    private final int[] bFromU = new int[CODES];

    // Float path. Signal space first, then linear light, then sRGB.
    private float[] wY;
    private float[] wRfromV;
    private float[] wGfromV;
    private float[] wGfromU;
    private float[] wBfromU;
    private float[] toLinear;
    private float[] ootfGain;
    private byte[] toSrgbLow;
    private byte[] toSrgbHigh;
    /** Linear 0..1 to its 1/2.4 gamma signal, and back. BT.2446 works in that domain. */
    private float[] gammaEncode24;
    private float[] gammaDecode24;
    /** The luma tone map of §4.1 steps 1 to 3, collapsed into one curve as the report allows. */
    private float[] lumaToneMap;
    /** The smooth core as one table: [luma][cb node][cr node], three shorts each. */
    private short[] coreLut;
    /** One flag per chroma cell per luma row: interpolation misleads here. */
    private byte[] wideHot;
    /** Scales display-linear so 1.0 means the 1 000 cd/m2 the report assumes. */
    private float toPeakNormalized;
    private float hlgScale;
    private float lumaR;
    private float lumaG;
    private float lumaB;

    ClipColor(int standard, int transfer, boolean fullRange) {
        this(standard, transfer, fullRange, OUTPUT_SDR_REC709);
    }

    ClipColor(int standard, int transfer, boolean fullRange, int outputSpace) {
        this.standard = standard;
        this.transfer = transfer;
        this.fullRange = fullRange;
        this.outputSpace = outputSpace;
        if (outputSpace != OUTPUT_SDR_REC709) {
            // Loudly, rather than returning SDR pixels that something upstream
            // will label BT.2020 HLG. A film tagged as HDR and carrying SDR
            // samples is the shape of defect this file exists to prevent.
            throw new IllegalArgumentException(
                    "output space " + outputSpace + " is declared but not implemented");
        }
        this.wideGamutOrHdr = standard == STANDARD_BT2020 || transfer != TRANSFER_SDR;
        this.compressHighlights = transfer != TRANSFER_SDR;

        final double kr = standard == STANDARD_BT601 ? 0.299
                : standard == STANDARD_BT2020 ? 0.2627 : 0.2126;
        final double kb = standard == STANDARD_BT601 ? 0.114
                : standard == STANDARD_BT2020 ? 0.0593 : 0.0722;
        final double kg = 1.0 - kr - kb;

        // Limited range puts black at 16 and white at 235 for luma, and centers
        // chroma on 128 with 224 codes of swing. Full range uses all 256. Get
        // this wrong and the picture is merely low contrast, which reads as a
        // bad clip rather than as a bug.
        final double yScale = fullRange ? 1.0 / CODE_MAX : 1.0 / LIMITED_LUMA_SPAN;
        final double yOffset = fullRange ? 0.0 : LIMITED_LUMA_OFFSET;
        final double cScale = fullRange ? 1.0 / CODE_MAX : 1.0 / LIMITED_CHROMA_SPAN;

        final double rv = 2.0 * (1.0 - kr);
        final double bu = 2.0 * (1.0 - kb);
        final double gv = -2.0 * (1.0 - kr) * kr / kg;
        final double gu = -2.0 * (1.0 - kb) * kb / kg;

        for (int i = 0; i < CODES; i++) {
            final double y = (i - yOffset) * yScale;
            final double c = (i - CHROMA_CENTRE) * cScale;
            yTerm[i] = (int) Math.round(y * 255.0 * 65536.0);
            rFromV[i] = (int) Math.round(c * rv * 255.0 * 65536.0);
            bFromU[i] = (int) Math.round(c * bu * 255.0 * 65536.0);
            gFromV[i] = (int) Math.round(c * gv * 255.0 * 65536.0);
            gFromU[i] = (int) Math.round(c * gu * 255.0 * 65536.0);
        }

        if (wideGamutOrHdr) {
            buildWidePath(kr, kg, kb, yScale, yOffset, cScale, rv, bu, gv, gu);
            buildCoreLut();
        }
    }

    /**
     * Samples the smooth core at every luma code and every 64th chroma code.
     *
     * <p>The core values come from the same float arithmetic as
     * {@link #wideChain}'s front half, so on the grid — which includes the
     * whole neutral axis the accuracy tests walk — the only thing this change
     * adds is the epilogue's fixed point, under a quarter of a code. The
     * Apple port builds its table from its own line-for-line copy of the same
     * arithmetic, which is what keeps the two platforms byte-identical
     * through this change.
     */
    private void buildCoreLut() {
        coreLut = new short[CODES * CHROMA_NODES * CHROMA_NODES * 3];
        final float[] tone = new float[3];
        int at = 0;
        for (int y = 0; y < CODES; y++) {
            for (int cb = 0; cb < CHROMA_NODES; cb++) {
                // The last node would sit at code 1024; the chain's tables stop
                // at 1023. Clamping shortens the top cell by one code, which
                // shows up as at most a sixty-fourth of a code's slope.
                final int u = Math.min(cb << CHROMA_CELL_SHIFT, CODES - 1);
                for (int cr = 0; cr < CHROMA_NODES; cr++) {
                    final int v = Math.min(cr << CHROMA_CELL_SHIFT, CODES - 1);
                    coreTone(y, u, v, tone);
                    coreLut[at++] = toneShort(tone[0]);
                    coreLut[at++] = toneShort(tone[1]);
                    coreLut[at++] = toneShort(tone[2]);
                }
            }
        }
        markHotCells();
    }

    /**
     * Flags every cell where interpolation cannot stand in for the chain.
     *
     * <p>Two shapes of failure, measured before they were understood. First,
     * the one clamp that cannot move into the epilogue: {@code clamp01} on
     * the signal before the transfer function, whose result everything
     * downstream — including the cross-channel scene luminance — consumes.
     * It kinked the interpolant by 43 codes where a bright signal crossed 1.0
     * mid-cell. Second, no kink at all: near the gamut boundary an output
     * channel sits close to zero, where the sRGB encode's slope is steepest,
     * and it amplified a half-percent of smooth interpolation curvature into
     * 28 codes where red lifts off from black. A finer grid buys almost
     * nothing against either — the first is a slope discontinuity and the
     * second a two-hundredfold amplification — so both fall back to the
     * chain.
     *
     * <p>Marked analytically, not by probing. A probing pass was tried and
     * could not certify a cell: with the amplification at thousands of codes
     * per tone unit, the error swings from zero to 25 codes between probe
     * points sixteen codes apart. The analytic conditions are decidable from
     * the corners alone — the pre-clamp signals are <em>linear</em> in the
     * chroma pair, so straddling a clamp bound shows at the corners, and the
     * near-zero shell is read off the corner nodes' own epilogue. The dense
     * sweep in the commit message is what says the two conditions are the
     * whole list.
     *
     * <p>On the corpus drone frame — a dawn sky that leans on the gamut
     * boundary — the flagged cells hold about a quarter of the pixels, which
     * still leaves the frame twice as fast as the chain everywhere; frames
     * that stay inside the gamut stay entirely on the fast path.
     */
    private void markHotCells() {
        final int cells = CODES >> CHROMA_CELL_SHIFT;
        wideHot = new byte[CODES * cells * cells];
        for (int y = 0; y < CODES; y++) {
            final float base = wY[y];
            for (int cu = 0; cu < cells; cu++) {
                final int u0 = cu << CHROMA_CELL_SHIFT;
                final int u1 = Math.min(u0 + (1 << CHROMA_CELL_SHIFT), CODES - 1);
                for (int cv = 0; cv < cells; cv++) {
                    final int v0 = cv << CHROMA_CELL_SHIFT;
                    final int v1 = Math.min(v0 + (1 << CHROMA_CELL_SHIFT), CODES - 1);
                    final boolean kinked = straddles(
                            base + wRfromV[v0], base + wRfromV[v1])
                            || straddles(base + wBfromU[u0], base + wBfromU[u1])
                            || straddles4(
                            base + wGfromU[u0] + wGfromV[v0],
                            base + wGfromU[u0] + wGfromV[v1],
                            base + wGfromU[u1] + wGfromV[v0],
                            base + wGfromU[u1] + wGfromV[v1]);
                    if (kinked || nearZeroShell(y, cu, cv)) {
                        wideHot[(y << 8) | (cu << 4) | cv] = 1;
                    }
                }
            }
        }
    }

    /**
     * Whether an output channel crosses black inside this cell.
     *
     * <p>Read off the corner nodes' own epilogue: the channel's post-matrix
     * linear values straddle zero. That is the takeoff — where red lifts off
     * from black at the edge of the Rec.709 gamut — and it is where the sRGB
     * encode's slope is steepest, so the same half-percent of interpolation
     * curvature that is invisible in a sky was measured at 25 codes here.
     * Away from the crossing the slope falls off fast: on the corpus drone
     * frame the residual is four codes at worst, and the dense sweep's worst
     * is nine — at chroma past ninety percent saturation, colors no camera
     * emits. Widening the shell to cover those was tried and rejected: it
     * bought one code and doubled the pixels paying full price.
     */
    private boolean nearZeroShell(int y, int cu, int cv) {
        float lo0 = Float.MAX_VALUE;
        float hi0 = -Float.MAX_VALUE;
        float lo1 = Float.MAX_VALUE;
        float hi1 = -Float.MAX_VALUE;
        float lo2 = Float.MAX_VALUE;
        float hi2 = -Float.MAX_VALUE;
        for (int du = 0; du <= 1; du++) {
            for (int dv = 0; dv <= 1; dv++) {
                final int i = ((y * CHROMA_NODES + cu + du) * CHROMA_NODES
                        + cv + dv) * 3;
                float r = decode24(coreLut[i]);
                float g = decode24(coreLut[i + 1]);
                float b = decode24(coreLut[i + 2]);
                if (standard == STANDARD_BT2020) {
                    final float lr = r;
                    final float lg = g;
                    final float lb = b;
                    r = 1.660491f * lr - 0.587641f * lg - 0.072850f * lb;
                    g = -0.124550f * lr + 1.132900f * lg - 0.008350f * lb;
                    b = -0.018151f * lr - 0.100579f * lg + 1.118730f * lb;
                }
                lo0 = Math.min(lo0, r);
                hi0 = Math.max(hi0, r);
                lo1 = Math.min(lo1, g);
                hi1 = Math.max(hi1, g);
                lo2 = Math.min(lo2, b);
                hi2 = Math.max(hi2, b);
            }
        }
        return (lo0 < 0.0f && hi0 > 0.0f) || (lo1 < 0.0f && hi1 > 0.0f)
                || (lo2 < 0.0f && hi2 > 0.0f);
    }

    /** Whether [a]..[b] crosses either clamp bound with both sides inside the cell. */
    private static boolean straddles(float a, float b) {
        final float lo = Math.min(a, b);
        final float hi = Math.max(a, b);
        return (lo < 0.0f && hi > 0.0f) || (lo < 1.0f && hi > 1.0f);
    }

    private static boolean straddles4(float a, float b, float c, float d) {
        final float lo = Math.min(Math.min(a, b), Math.min(c, d));
        final float hi = Math.max(Math.max(a, b), Math.max(c, d));
        return (lo < 0.0f && hi > 0.0f) || (lo < 1.0f && hi > 1.0f);
    }

    private static short toneShort(float tone) {
        // floor(x + 0.5), not Math.round-vs-lroundf: those two disagree on
        // negative halves, and an out-of-gamut node is negative.
        final int q = (int) Math.floor(tone * TONE_ONE + 0.5f);
        if (q < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        if (q > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        return (short) q;
    }

    private void buildWidePath(double kr, double kg, double kb,
                               double yScale, double yOffset, double cScale,
                               double rv, double bu, double gv, double gu) {
        lumaR = (float) kr;
        lumaG = (float) kg;
        lumaB = (float) kb;

        wY = new float[CODES];
        wRfromV = new float[CODES];
        wGfromV = new float[CODES];
        wGfromU = new float[CODES];
        wBfromU = new float[CODES];
        for (int i = 0; i < CODES; i++) {
            final double y = (i - yOffset) * yScale;
            final double c = (i - CHROMA_CENTRE) * cScale;
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

        // The decode half of the 1/2.4 pair is built for every wide path, not
        // just the tone-mapped ones: the table epilogue decodes through it
        // even when the core is an SDR transfer's linear light re-encoded.
        gammaDecode24 = new float[TONE_LUT];
        for (int i = 0; i < TONE_LUT; i++) {
            gammaDecode24[i] = (float) Math.pow((double) i / (TONE_LUT - 1), 2.4);
        }

        if (compressHighlights) {
            buildToneMap();
        }

        toSrgbLow = new byte[ENCODE_LOW_LUT];
        for (int i = 0; i < ENCODE_LOW_LUT; i++) {
            final double x = ENCODE_SPLIT * i / (ENCODE_LOW_LUT - 1);
            toSrgbLow[i] = (byte) Math.round(255.0 * srgbEncode(x));
        }
        toSrgbHigh = new byte[ENCODE_HIGH_LUT];
        for (int i = 0; i < ENCODE_HIGH_LUT; i++) {
            final double x = ENCODE_SPLIT
                    + (1.0 - ENCODE_SPLIT) * i / (ENCODE_HIGH_LUT - 1);
            toSrgbHigh[i] = (byte) Math.round(255.0 * srgbEncode(x));
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

    /**
     * The wide path per pixel: bilinear over the core table, then the exact
     * epilogue — twelve short loads and integer weights for the core, then
     * the chain's own tail. This is where the 200 ms a frame went: not into
     * any one step of {@link #wideChain} but into running all of them per
     * pixel. The hot cells — the entry clamp's kinks and the gamut
     * boundary's takeoff — still take the chain, because there a straight
     * line would lie by tens of codes.
     */
    private int toRgbWide(int y, int u, int v) {
        if (wideHot[(y << 8) | ((u >> CHROMA_CELL_SHIFT) << 4)
                | (v >> CHROMA_CELL_SHIFT)] != 0) {
            return wideChain(y, u, v);
        }
        return tablePath(y, u, v);
    }

    /** Bilinear over the core table, then the exact epilogue. */
    private int tablePath(int y, int u, int v) {
        final int ub = u >> CHROMA_CELL_SHIFT;
        final int uf = u & CHROMA_CELL_MASK;
        final int vb = v >> CHROMA_CELL_SHIFT;
        final int vf = v & CHROMA_CELL_MASK;
        // (64-uf)(64-vf), (64-uf)vf, uf(64-vf), uf*vf — summing to 4096, so
        // the interpolated tone keeps the nodes' Q12 scale.
        final int w11 = uf * vf;
        final int w01 = (vf << CHROMA_CELL_SHIFT) - w11;
        final int w10 = (uf << CHROMA_CELL_SHIFT) - w11;
        final int w00 = TONE_ONE - w01 - w10 - w11;
        final int i = ((y * CHROMA_NODES + ub) * CHROMA_NODES + vb) * 3;
        final int j = i + CHROMA_NODES * 3;
        final int tr = (coreLut[i] * w00 + coreLut[i + 3] * w01
                + coreLut[j] * w10 + coreLut[j + 3] * w11 + TONE_ONE / 2) >> 12;
        final int tg = (coreLut[i + 1] * w00 + coreLut[i + 4] * w01
                + coreLut[j + 1] * w10 + coreLut[j + 4] * w11 + TONE_ONE / 2) >> 12;
        final int tb = (coreLut[i + 2] * w00 + coreLut[i + 5] * w01
                + coreLut[j + 2] * w10 + coreLut[j + 5] * w11 + TONE_ONE / 2) >> 12;

        // The epilogue: every clamp in the conversion lives below this line,
        // applied to the interpolated value rather than baked into the nodes,
        // so no clamp ever kinks what the bilinear above has to represent.
        // It is the chain's own tail — its decode table, its matrix literals,
        // its encode — so the only arithmetic this path adds is integer.
        float r = decode24(tr);
        float g = decode24(tg);
        float b = decode24(tb);
        if (standard == STANDARD_BT2020) {
            final float lr = r;
            final float lg = g;
            final float lb = b;
            r = 1.660491f * lr - 0.587641f * lg - 0.072850f * lb;
            g = -0.124550f * lr + 1.132900f * lg - 0.008350f * lb;
            b = -0.018151f * lr - 0.100579f * lg + 1.118730f * lb;
        }
        return (srgbByte(r) << 16) | (srgbByte(g) << 8) | srgbByte(b);
    }

    /**
     * The chain's 2.4 gamma decode for a Q12 tone value, clamp included.
     *
     * <p>The index arithmetic is all-integer and exactly reproduces
     * {@code sampled}'s {@code (int) (x * 4095 + 0.5f)} for {@code x = t/4096}:
     * {@code t*4095/4096 + 1/2} floors to {@code (t*4095 + 2048) >> 12}.
     */
    private float decode24(int t) {
        if (t < 0) {
            t = 0;
        } else if (t > TONE_ONE) {
            t = TONE_ONE;
        }
        return gammaDecode24[(t * (TONE_LUT - 1) + TONE_ONE / 2) >> 12];
    }

    /**
     * The smooth core: signal to unclamped tone-domain R'G'B', the same float
     * arithmetic as {@link #wideChain}'s front half. Runs only while
     * {@link #buildCoreLut} samples it.
     */
    private void coreTone(int y, int u, int v, float[] out) {
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

        if (compressHighlights) {
            // Report ITU-R BT.2446-1 §4.1, Tables 2 and 3, stopping just short
            // of Table 3's final clamp — that belongs to the epilogue.
            final float rp = sampled(gammaEncode24, r * toPeakNormalized);
            final float gp = sampled(gammaEncode24, g * toPeakNormalized);
            final float bp = sampled(gammaEncode24, b * toPeakNormalized);

            final float yHdr = 0.2627f * rp + 0.6780f * gp + 0.0593f * bp;
            final float ySdr = sampled(lumaToneMap, yHdr);

            float cb = 0.0f;
            float cr = 0.0f;
            if (yHdr > 0.0f) {
                final float f = ySdr / (1.1f * yHdr);
                cb = f * (bp - yHdr) / 1.8814f;
                cr = f * (rp - yHdr) / 1.4746f;
            }
            final float yTmo = ySdr - Math.max(0.1f * cr, 0.0f);

            out[0] = yTmo + 1.4746f * cr;
            out[2] = yTmo + 1.8814f * cb;
            out[1] = yTmo
                    - (0.2627f * 1.4746f / 0.6780f) * cr
                    - (0.0593f * 1.8814f / 0.6780f) * cb;
            return;
        }

        // Wide gamut with an SDR transfer: no tone mapping, so the core is the
        // linear light lifted into the same 1/2.4 domain the epilogue decodes
        // from. The roundtrip costs under a quarter of a code.
        out[0] = (float) Math.pow(clamp01(r), 1.0 / 2.4);
        out[1] = (float) Math.pow(clamp01(g), 1.0 / 2.4);
        out[2] = (float) Math.pow(clamp01(b), 1.0 / 2.4);
    }

    /**
     * The full conversion, evaluated rather than looked up: the original
     * arithmetic, unchanged. Per pixel it runs only inside cells the entry
     * clamp kinks; everywhere else it is the reference {@code ClipColorTest}
     * holds the table path against. Package visible for exactly that test.
     */
    int wideChain(int y, int u, int v) {
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

        if (compressHighlights) {
            // Report ITU-R BT.2446-1 §4.1, Tables 2 and 3.
            final float rp = sampled(gammaEncode24, r * toPeakNormalized);
            final float gp = sampled(gammaEncode24, g * toPeakNormalized);
            final float bp = sampled(gammaEncode24, b * toPeakNormalized);

            final float yHdr = 0.2627f * rp + 0.6780f * gp + 0.0593f * bp;
            final float ySdr = sampled(lumaToneMap, yHdr);

            // Table 3. **The half an invented curve does not have.** Lowering
            // the luminance of a picture changes how saturated its colours look,
            // so the chroma has to be rescaled against how far the luma moved or
            // the result reads as washed out at exactly the brightnesses the
            // tone map touched most.
            float cb = 0.0f;
            float cr = 0.0f;
            if (yHdr > 0.0f) {
                final float f = ySdr / (1.1f * yHdr);
                cb = f * (bp - yHdr) / 1.8814f;
                cr = f * (rp - yHdr) / 1.4746f;
            }
            final float yTmo = ySdr - Math.max(0.1f * cr, 0.0f);

            // Back to R'G'B', still BT.2020 and still in the 1/2.4 domain.
            final float rTmo = yTmo + 1.4746f * cr;
            final float bTmo = yTmo + 1.8814f * cb;
            final float gTmo = yTmo
                    - (0.2627f * 1.4746f / 0.6780f) * cr
                    - (0.0593f * 1.8814f / 0.6780f) * cb;

            r = sampled(gammaDecode24, clamp01(rTmo));
            g = sampled(gammaDecode24, clamp01(gTmo));
            b = sampled(gammaDecode24, clamp01(bTmo));
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

    /**
     * The tables for Report ITU-R BT.2446-1 §4.1, "conversion Method A".
     *
     * <p><b>Why a published method rather than a curve of our own.</b> The first
     * attempt here was an invented exponential shoulder, and it was wrong in two
     * ways that reading the report made obvious. It bent the curve in *linear
     * light*, where a knee does not correspond to anything the eye does —
     * Method A moves into a perceptual domain first, bends there, and comes back.
     * And it left chroma untouched, so the picture darkened while its colours did
     * not, which the report addresses directly: brightness and perceived
     * saturation interact (the Hunt effect), so a conversion that changes one
     * must correct the other.
     *
     * <p>Method A rather than B or C, and the report says what each is for. B is
     * aimed at live SDR sources whose over-exposed areas must not be amplified on
     * the way *up*. C is parametric, tuned per content from skin tones. A targets
     * "movies and episodic content … produced to a high visual quality", which is
     * what a drone clip is. It is also the one with an independent implementation
     * to check against — `bt.2446a` in libplacebo, which mpv, VLC and ffmpeg use
     * — and that matters more than the curve's shape: it is the difference
     * between a documented method and matching whatever AVFoundation happened to
     * do this year.
     *
     * <p>Sampled into tables rather than evaluated per pixel. The report notes
     * the luma path can be a single 1-D look-up, and a {@code Math.pow} per
     * channel is exactly the cost that put 400 ms on a frame once already.
     */
    private void buildToneMap() {
        // Normalize display light so 1.0 is the report's 1 000 cd/m2. The HLG
        // path already peaks at 1.0 before `hlgScale` lifts diffuse white, so
        // undo that lift; PQ arrives normalized to 203 cd/m2 instead.
        toPeakNormalized = transfer == TRANSFER_HLG
                ? 1.0f / hlgScale
                : (float) (PQ_REFERENCE_WHITE_NITS / TONE_MAP_HDR_NITS);

        gammaEncode24 = new float[TONE_LUT];
        for (int i = 0; i < TONE_LUT; i++) {
            gammaEncode24[i] = (float) Math.pow((double) i / (TONE_LUT - 1), 1.0 / 2.4);
        }

        final double rhoHdr =
                1.0 + 32.0 * Math.pow(TONE_MAP_HDR_NITS / 10000.0, 1.0 / 2.4);
        final double rhoSdr =
                1.0 + 32.0 * Math.pow(TONE_MAP_SDR_NITS / 10000.0, 1.0 / 2.4);

        lumaToneMap = new float[TONE_LUT];
        for (int i = 0; i < TONE_LUT; i++) {
            final double yHdr = (double) i / (TONE_LUT - 1);

            // Step 1: into the perceptual domain.
            final double yp = Math.log(1.0 + (rhoHdr - 1.0) * yHdr) / Math.log(rhoHdr);

            // Step 2: the knee, stated there and nowhere else.
            final double yc;
            if (yp <= 0.7399) {
                yc = 1.0770 * yp;
            } else if (yp < 0.9909) {
                yc = -1.1510 * yp * yp + 2.7811 * yp - 0.6302;
            } else {
                yc = 0.5000 * yp + 0.5000;
            }

            // Step 3: back out, against the SDR display this time.
            lumaToneMap[i] = (float) ((Math.pow(rhoSdr, yc) - 1.0) / (rhoSdr - 1.0));
        }
    }

    private float sampled(float[] table, float x) {
        if (x <= 0.0f) {
            return table[0];
        }
        if (x >= 1.0f) {
            return table[table.length - 1];
        }
        return table[(int) (x * (table.length - 1) + 0.5f)];
    }

    private int srgbByte(float linear) {
        if (linear <= 0.0f) {
            return 0;
        }
        if (linear >= 1.0f) {
            return 255;
        }
        if (linear < ENCODE_SPLIT) {
            return toSrgbLow[(int) (linear * ((ENCODE_LOW_LUT - 1) / ENCODE_SPLIT) + 0.5f)]
                    & 0xFF;
        }
        return toSrgbHigh[(int) ((linear - ENCODE_SPLIT)
                * ((ENCODE_HIGH_LUT - 1) / (1.0f - ENCODE_SPLIT)) + 0.5f)] & 0xFF;
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
