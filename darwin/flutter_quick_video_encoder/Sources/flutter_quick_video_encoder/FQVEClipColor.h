#import <Foundation/Foundation.h>

/// YCbCr to sRGB for clip footage, on the Apple side.
///
/// **This exists because AVFoundation was doing this job invisibly.** Asking an
/// `AVAssetReaderTrackOutput` for BGRA with Rec.709 colour properties hands back
/// pixels somebody else converted, with a tone map that cannot be seen, matched
/// or reproduced — so the same drone clip came out of an iPhone looking
/// different from the same clip out of a Galaxy, and neither platform could be
/// pointed at a specification. Converting here instead makes the two agree by
/// running the same arithmetic, and makes an HDR output space a choice rather
/// than something AVFoundation has already decided.
///
/// The arithmetic is the Java `ClipColor`, line for line. Where they differ,
/// they are wrong: `ClipColorTest` and its Objective-C counterpart derive the
/// same expected values from the same published constants.
///
/// **Codes are 10-bit — 0 to 1023 — on both platforms.** Apple hands back
/// `420YpCbCr10BiPlanarVideoRange` and Android will hand back `YCBCR_P010`, so
/// ten bits is the shared convention and an 8-bit source is shifted up rather
/// than the tables being built twice.
typedef struct FQVEClipColor FQVEClipColor;

/// Colour primaries, matching `ClipColor.STANDARD_*`.
typedef NS_ENUM(int, FQVEStandard) {
    FQVEStandardBT601 = 1,
    FQVEStandardBT709 = 2,
    FQVEStandardBT2020 = 3,
};

/// Transfer function, matching `ClipColor.TRANSFER_*`.
typedef NS_ENUM(int, FQVETransfer) {
    FQVETransferSDR = 1,
    FQVETransferHLG = 2,
    FQVETransferPQ = 3,
};

/// Builds the tables. Free with `FQVEClipColorRelease`.
FQVEClipColor *FQVEClipColorCreate(FQVEStandard standard,
                                   FQVETransfer transfer,
                                   BOOL fullRange);

void FQVEClipColorRelease(FQVEClipColor *color);

/// One YCbCr triple, 10-bit codes, as packed 0xRRGGBB.
uint32_t FQVEClipColorToRgb(const FQVEClipColor *color, int y, int cb, int cr);

/// What this run is converting, for the log.
NSString *FQVEClipColorDescribe(const FQVEClipColor *color);
