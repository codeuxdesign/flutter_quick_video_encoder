#import "FQVEClipColor.h"

#import <math.h>
#import <stdlib.h>

// Sizes and split point are the Java file's, deliberately. Two implementations
// of one conversion agree only if they quantise the same way; a table half the
// size here would put the platforms a code apart in the shadows and nobody would
// know which was right.
static const int kLinearLut = 1024;
static const int kGainLut = 4096;
static const int kToneLut = 4096;
static const float kEncodeSplit = 0.02f;
static const int kEncodeLowLut = 2048;
static const int kEncodeHighLut = 4096;

static const double kHlgGamma = 1.2;
static const double kHlgDiffuseWhiteSignal = 0.75;
static const double kPqReferenceWhiteNits = 203.0;
static const double kToneMapHdrNits = 1000.0;
static const double kToneMapSdrNits = 100.0;

/// 10-bit limited range: luma 64..940, chroma centred on 512 with 896 of swing.
static const double kCodeMax = 1023.0;
static const double kLimitedLumaOffset = 64.0;
static const double kLimitedLumaSpan = 876.0;
static const double kChromaCentre = 512.0;
static const double kLimitedChromaSpan = 896.0;

struct FQVEClipColor {
    FQVEStandard standard;
    FQVETransfer transfer;
    BOOL fullRange;
    BOOL wideGamutOrHdr;
    BOOL compressHighlights;

    // Integer path, 16.16 fixed point, one table per input code. Ordinary
    // Rec.709 and Rec.601 video takes this and never reaches linear light.
    int32_t *iY, *iRfromV, *iGfromV, *iGfromU, *iBfromU;  // 1024 each

    float *wY, *wRfromV, *wGfromV, *wGfromU, *wBfromU;   // 1024 each
    float *toLinear;                                      // kLinearLut
    float *ootfGain;                                      // kGainLut
    float *gammaEncode24, *gammaDecode24, *lumaToneMap;   // kToneLut
    uint8_t *toSrgbLow, *toSrgbHigh;

    float hlgScale;
    float toPeakNormalized;
    float lumaR, lumaG, lumaB;
};

static double HlgSignalToScene(double signal) {
    const double a = 0.17883277;
    const double b = 1.0 - 4.0 * a;
    const double c = 0.5 - a * log(4.0 * a);
    if (signal <= 0.5) {
        return signal * signal / 3.0;
    }
    return (exp((signal - c) / a) + b) / 12.0;
}

static double PqSignalToNits(double signal) {
    const double m1 = 2610.0 / 16384.0;
    const double m2 = 2523.0 / 4096.0 * 128.0;
    const double c1 = 3424.0 / 4096.0;
    const double c2 = 2413.0 / 4096.0 * 32.0;
    const double c3 = 2392.0 / 4096.0 * 32.0;
    const double e = pow(signal > 0.0 ? signal : 0.0, 1.0 / m2);
    const double num = (e - c1) > 0.0 ? (e - c1) : 0.0;
    const double den = c2 - c3 * e;
    if (den <= 0.0) {
        return 10000.0;
    }
    return 10000.0 * pow(num / den, 1.0 / m1);
}

static double SignalToLinear(FQVETransfer transfer, double signal) {
    switch (transfer) {
        case FQVETransferHLG:
            return HlgSignalToScene(signal);
        case FQVETransferPQ:
            return PqSignalToNits(signal) / kPqReferenceWhiteNits;
        default:
            // Rec.709's OETF inverted. Only runs for BT.2020 with an SDR
            // transfer, which still needs linear light to cross primaries.
            if (signal < 0.081) {
                return signal / 4.5;
            }
            return pow((signal + 0.099) / 1.099, 1.0 / 0.45);
    }
}

static double SrgbEncode(double linear) {
    if (linear <= 0.0031308) {
        return 12.92 * linear;
    }
    return 1.055 * pow(linear, 1.0 / 2.4) - 0.055;
}

FQVEClipColor *FQVEClipColorCreate(FQVEStandard standard,
                                   FQVETransfer transfer,
                                   BOOL fullRange) {
    FQVEClipColor *c = calloc(1, sizeof(FQVEClipColor));
    c->standard = standard;
    c->transfer = transfer;
    c->fullRange = fullRange;
    c->wideGamutOrHdr = standard == FQVEStandardBT2020 || transfer != FQVETransferSDR;
    c->compressHighlights = transfer != FQVETransferSDR;

    const double kr = standard == FQVEStandardBT601 ? 0.299
                    : standard == FQVEStandardBT2020 ? 0.2627 : 0.2126;
    const double kb = standard == FQVEStandardBT601 ? 0.114
                    : standard == FQVEStandardBT2020 ? 0.0593 : 0.0722;
    const double kg = 1.0 - kr - kb;
    c->lumaR = (float)kr;
    c->lumaG = (float)kg;
    c->lumaB = (float)kb;

    const double yScale = fullRange ? 1.0 / kCodeMax : 1.0 / kLimitedLumaSpan;
    const double yOffset = fullRange ? 0.0 : kLimitedLumaOffset;
    const double cScale = fullRange ? 1.0 / kCodeMax : 1.0 / kLimitedChromaSpan;

    const double rv = 2.0 * (1.0 - kr);
    const double bu = 2.0 * (1.0 - kb);
    const double gv = -2.0 * (1.0 - kr) * kr / kg;
    const double gu = -2.0 * (1.0 - kb) * kb / kg;

    const int codes = 1024;

    // Built unconditionally, exactly as the Java side builds them, because the
    // two have to agree bit for bit and the cheapest way to guarantee that is to
    // run the same arithmetic rather than an equivalent one.
    c->iY = malloc(sizeof(int32_t) * codes);
    c->iRfromV = malloc(sizeof(int32_t) * codes);
    c->iGfromV = malloc(sizeof(int32_t) * codes);
    c->iGfromU = malloc(sizeof(int32_t) * codes);
    c->iBfromU = malloc(sizeof(int32_t) * codes);
    for (int i = 0; i < codes; i++) {
        const double y = (i - yOffset) * yScale;
        const double ch = (i - kChromaCentre) * cScale;
        c->iY[i] = (int32_t)llround(y * 255.0 * 65536.0);
        c->iRfromV[i] = (int32_t)llround(ch * rv * 255.0 * 65536.0);
        c->iBfromU[i] = (int32_t)llround(ch * bu * 255.0 * 65536.0);
        c->iGfromV[i] = (int32_t)llround(ch * gv * 255.0 * 65536.0);
        c->iGfromU[i] = (int32_t)llround(ch * gu * 255.0 * 65536.0);
    }

    c->wY = malloc(sizeof(float) * codes);
    c->wRfromV = malloc(sizeof(float) * codes);
    c->wGfromV = malloc(sizeof(float) * codes);
    c->wGfromU = malloc(sizeof(float) * codes);
    c->wBfromU = malloc(sizeof(float) * codes);
    for (int i = 0; i < codes; i++) {
        const double y = (i - yOffset) * yScale;
        const double ch = (i - kChromaCentre) * cScale;
        c->wY[i] = (float)y;
        c->wRfromV[i] = (float)(ch * rv);
        c->wBfromU[i] = (float)(ch * bu);
        c->wGfromV[i] = (float)(ch * gv);
        c->wGfromU[i] = (float)(ch * gu);
    }

    c->toLinear = malloc(sizeof(float) * kLinearLut);
    for (int i = 0; i < kLinearLut; i++) {
        c->toLinear[i] = (float)SignalToLinear(transfer, (double)i / (kLinearLut - 1));
    }

    if (transfer == FQVETransferHLG) {
        c->ootfGain = malloc(sizeof(float) * kGainLut);
        for (int i = 0; i < kGainLut; i++) {
            c->ootfGain[i] = (float)pow((double)i / (kGainLut - 1), kHlgGamma - 1.0);
        }
        const double diffuse = HlgSignalToScene(kHlgDiffuseWhiteSignal);
        const double display = pow(diffuse, kHlgGamma - 1.0) * diffuse;
        c->hlgScale = (float)(1.0 / display);
    }

    if (c->compressHighlights) {
        c->toPeakNormalized = transfer == FQVETransferHLG
            ? 1.0f / c->hlgScale
            : (float)(kPqReferenceWhiteNits / kToneMapHdrNits);

        c->gammaEncode24 = malloc(sizeof(float) * kToneLut);
        c->gammaDecode24 = malloc(sizeof(float) * kToneLut);
        for (int i = 0; i < kToneLut; i++) {
            const double x = (double)i / (kToneLut - 1);
            c->gammaEncode24[i] = (float)pow(x, 1.0 / 2.4);
            c->gammaDecode24[i] = (float)pow(x, 2.4);
        }

        const double rhoHdr = 1.0 + 32.0 * pow(kToneMapHdrNits / 10000.0, 1.0 / 2.4);
        const double rhoSdr = 1.0 + 32.0 * pow(kToneMapSdrNits / 10000.0, 1.0 / 2.4);

        c->lumaToneMap = malloc(sizeof(float) * kToneLut);
        for (int i = 0; i < kToneLut; i++) {
            const double yHdr = (double)i / (kToneLut - 1);
            const double yp = log(1.0 + (rhoHdr - 1.0) * yHdr) / log(rhoHdr);
            double yc;
            if (yp <= 0.7399) {
                yc = 1.0770 * yp;
            } else if (yp < 0.9909) {
                yc = -1.1510 * yp * yp + 2.7811 * yp - 0.6302;
            } else {
                yc = 0.5000 * yp + 0.5000;
            }
            c->lumaToneMap[i] = (float)((pow(rhoSdr, yc) - 1.0) / (rhoSdr - 1.0));
        }
    }

    c->toSrgbLow = malloc(kEncodeLowLut);
    for (int i = 0; i < kEncodeLowLut; i++) {
        const double x = kEncodeSplit * i / (kEncodeLowLut - 1);
        c->toSrgbLow[i] = (uint8_t)lround(255.0 * SrgbEncode(x));
    }
    c->toSrgbHigh = malloc(kEncodeHighLut);
    for (int i = 0; i < kEncodeHighLut; i++) {
        const double x = kEncodeSplit + (1.0 - kEncodeSplit) * i / (kEncodeHighLut - 1);
        c->toSrgbHigh[i] = (uint8_t)lround(255.0 * SrgbEncode(x));
    }

    return c;
}

void FQVEClipColorRelease(FQVEClipColor *c) {
    if (!c) { return; }
    free(c->iY); free(c->iRfromV); free(c->iGfromV); free(c->iGfromU); free(c->iBfromU);
    free(c->wY); free(c->wRfromV); free(c->wGfromV); free(c->wGfromU); free(c->wBfromU);
    free(c->toLinear); free(c->ootfGain);
    free(c->gammaEncode24); free(c->gammaDecode24); free(c->lumaToneMap);
    free(c->toSrgbLow); free(c->toSrgbHigh);
    free(c);
}

/// A 16.16 sum, shifted down and pinned to a byte — `ClipColor.clamp255`.
static inline int ClampByte(int32_t v) {
    if (v < 0) { return 0; }
    return v > 255 ? 255 : (int)v;
}

static inline float Clamp01(float v) {
    if (v < 0.0f) { return 0.0f; }
    return v > 1.0f ? 1.0f : v;
}

static inline float Sampled(const float *table, int size, float x) {
    if (x <= 0.0f) { return table[0]; }
    if (x >= 1.0f) { return table[size - 1]; }
    return table[(int)(x * (size - 1) + 0.5f)];
}

static inline int SrgbByte(const FQVEClipColor *c, float linear) {
    if (linear <= 0.0f) { return 0; }
    if (linear >= 1.0f) { return 255; }
    if (linear < kEncodeSplit) {
        return c->toSrgbLow[(int)(linear * ((kEncodeLowLut - 1) / kEncodeSplit) + 0.5f)];
    }
    return c->toSrgbHigh[(int)((linear - kEncodeSplit)
        * ((kEncodeHighLut - 1) / (1.0f - kEncodeSplit)) + 0.5f)];
}

uint32_t FQVEClipColorToRgb(const FQVEClipColor *c, int y, int cb, int cr) {
    if (y < 0) { y = 0; } else if (y > 1023) { y = 1023; }
    if (cb < 0) { cb = 0; } else if (cb > 1023) { cb = 1023; }
    if (cr < 0) { cr = 0; } else if (cr > 1023) { cr = 1023; }

    // **Ordinary Rec.709 and Rec.601 video is a matrix and nothing else.**
    //
    // This branch existed on the Java side from the beginning and was missing
    // here — `wideGamutOrHdr` was computed with the identical expression two
    // hundred lines up and then never read, so every clip fell through to the
    // float path. For an SDR source that meant decoding the signal with Rec.709's
    // *camera* inverse OETF and then re-encoding it with sRGB's *display* curve,
    // which double-counts a transfer: the two are not inverses, and the round
    // trip lifts shadows and midtones by up to sixteen codes, +12 at mid gray,
    // converging only at white. Every ordinary clip came out of an iPhone
    // visibly flatter than the same clip out of a Galaxy, with nothing to say so.
    //
    // It hid because the one frame the two implementations were ever compared on
    // was HDR, which takes the float path on both platforms and therefore agrees.
    // The pairing that diverges is the one that comparison could not reach.
    //
    // Treating the signal as already display-encoded is the right answer as well
    // as the Java one: the composite blends these pixels straight into an sRGB
    // overlay, so sRGB space is where they need to arrive.
    if (!c->wideGamutOrHdr) {
        const int32_t base = c->iY[y];
        return ((uint32_t)ClampByte((base + c->iRfromV[cr]) >> 16) << 16)
             | ((uint32_t)ClampByte((base + c->iGfromU[cb] + c->iGfromV[cr]) >> 16) << 8)
             |  (uint32_t)ClampByte((base + c->iBfromU[cb]) >> 16);
    }

    const float base = c->wY[y];
    float r = Clamp01(base + c->wRfromV[cr]);
    float g = Clamp01(base + c->wGfromU[cb] + c->wGfromV[cr]);
    float b = Clamp01(base + c->wBfromU[cb]);

    r = Sampled(c->toLinear, kLinearLut, r);
    g = Sampled(c->toLinear, kLinearLut, g);
    b = Sampled(c->toLinear, kLinearLut, b);

    if (c->transfer == FQVETransferHLG) {
        float scene = c->lumaR * r + c->lumaG * g + c->lumaB * b;
        scene = Clamp01(scene);
        const float gain =
            c->ootfGain[(int)(scene * (kGainLut - 1) + 0.5f)] * c->hlgScale;
        r *= gain; g *= gain; b *= gain;
    }

    if (c->compressHighlights) {
        // Report ITU-R BT.2446-1 §4.1, Tables 2 and 3. Same steps, same
        // constants and same order as the Java side; see `ClipColor` for why
        // Method A and why the chroma correction is not optional.
        const float rp = Sampled(c->gammaEncode24, kToneLut, r * c->toPeakNormalized);
        const float gp = Sampled(c->gammaEncode24, kToneLut, g * c->toPeakNormalized);
        const float bp = Sampled(c->gammaEncode24, kToneLut, b * c->toPeakNormalized);

        const float yHdr = 0.2627f * rp + 0.6780f * gp + 0.0593f * bp;
        const float ySdr = Sampled(c->lumaToneMap, kToneLut, yHdr);

        float chromaB = 0.0f;
        float chromaR = 0.0f;
        if (yHdr > 0.0f) {
            const float f = ySdr / (1.1f * yHdr);
            chromaB = f * (bp - yHdr) / 1.8814f;
            chromaR = f * (rp - yHdr) / 1.4746f;
        }
        const float yTmo = ySdr - (0.1f * chromaR > 0.0f ? 0.1f * chromaR : 0.0f);

        const float rTmo = yTmo + 1.4746f * chromaR;
        const float bTmo = yTmo + 1.8814f * chromaB;
        const float gTmo = yTmo
            - (0.2627f * 1.4746f / 0.6780f) * chromaR
            - (0.0593f * 1.8814f / 0.6780f) * chromaB;

        r = Sampled(c->gammaDecode24, kToneLut, Clamp01(rTmo));
        g = Sampled(c->gammaDecode24, kToneLut, Clamp01(gTmo));
        b = Sampled(c->gammaDecode24, kToneLut, Clamp01(bTmo));
    }

    if (c->standard == FQVEStandardBT2020) {
        const float lr = r, lg = g, lb = b;
        // Rows sum to one, which is what keeps white white.
        r =  1.660491f * lr - 0.587641f * lg - 0.072850f * lb;
        g = -0.124550f * lr + 1.132900f * lg - 0.008350f * lb;
        b = -0.018151f * lr - 0.100579f * lg + 1.118730f * lb;
    }

    return ((uint32_t)SrgbByte(c, r) << 16)
         | ((uint32_t)SrgbByte(c, g) << 8)
         |  (uint32_t)SrgbByte(c, b);
}

NSString *FQVEClipColorDescribe(const FQVEClipColor *c) {
    NSString *s = c->standard == FQVEStandardBT601 ? @"BT.601"
                : c->standard == FQVEStandardBT2020 ? @"BT.2020" : @"BT.709";
    NSString *t = c->transfer == FQVETransferHLG ? @"HLG"
                : c->transfer == FQVETransferPQ ? @"PQ" : @"SDR";
    return [NSString stringWithFormat:@"%@/%@/%@", s, t, c->fullRange ? @"full" : @"limited"];
}
