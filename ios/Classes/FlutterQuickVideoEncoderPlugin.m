#import "FlutterQuickVideoEncoderPlugin.h"
#import "FQVEClipColor.h"
#import <Foundation/Foundation.h>

#import <AVFoundation/AVFoundation.h>
#import <AVFoundation/AVAssetWriter.h>
#import <AVFoundation/AVAssetWriterInput.h>
#import <AVFoundation/AVMediaFormat.h>

#import <CoreMedia/CoreMedia.h> 
#import <CoreMedia/CMFormatDescription.h>
#import <CoreMedia/CMSampleBuffer.h>

#define kOutputBus 0
#define NAMESPACE @"flutter_quick_video_encoder" 

// forward define
CMSampleBufferRef createVideoSampleBuffer(int fps, int videoFrameIdx, int width, int height, NSData *videoFrameData);
CMSampleBufferRef createAudioSampleBuffer(int fps, int audioFrameIdx, int audioChannels, int sampleRate, NSData *audioSampleData);

@class FQVEClipReader;

/// The first track of [type], with the asset's `tracks` loaded before it is read.
///
/// **`tracksWithMediaType:` on its own can return an empty array for a file that
/// is perfectly valid.** Since iOS 15 and macOS 12 the synchronous `AVAsset`
/// accessors no longer block to load the property they are reading — they answer
/// with whatever has been loaded so far, which for an asset created moments ago
/// is nothing. It is timing-dependent, so it fails intermittently and passes
/// under a debugger.
///
/// Observed once: a mux refused a freshly written render with `MuxNoVideoTrack`
/// on a file `ffprobe` reads as 4339 frames of h264. `finish` had already waited
/// on `finishWritingWithCompletionHandler`, so the file was complete; the reader
/// simply had not looked yet.
///
/// **One helper rather than three call sites, because the three fail
/// differently.** In the mux an empty result becomes a FlutterError, which is
/// loud. In `FQVEClipReader` it becomes a nil reader, and the compositor's
/// documented behaviour is to leave that rectangle as the painter cleared it —
/// which encodes as a *black window in the film with no error anywhere*. Fixing
/// the loud one and leaving the silent one is the worse outcome of the two, and
/// a shared helper is what makes that impossible.
///
/// `loadValuesAsynchronouslyForKeys:` rather than the newer
/// `loadTracksWithMediaType:completionHandler:`, which would raise the
/// deployment target for no benefit here. The wait is bounded: a load that never
/// completes returns nil rather than hanging the platform thread, because a
/// wedged export is harder to diagnose than a refused one.
static AVAssetTrack *FQVEFirstTrack(AVURLAsset *asset, AVMediaType type) {
    if (!asset) { return nil; }
    dispatch_semaphore_t loaded = dispatch_semaphore_create(0);
    [asset loadValuesAsynchronouslyForKeys:@[ @"tracks" ]
                         completionHandler:^{ dispatch_semaphore_signal(loaded); }];
    if (dispatch_semaphore_wait(
            loaded, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
        NSLog(@"FQVE: timed out loading tracks for %@", asset.URL.lastPathComponent);
        return nil;
    }
    NSError *error = nil;
    if ([asset statusOfValueForKey:@"tracks" error:&error] != AVKeyValueStatusLoaded) {
        NSLog(@"FQVE: tracks did not load for %@: %@",
              asset.URL.lastPathComponent, error);
        return nil;
    }
    AVAssetTrack *track = [[asset tracksWithMediaType:type] firstObject];
    if (!track) {
        // Say this positively. The two ways to get nil from here are "the tracks
        // never loaded" and "they loaded and there are none", and they call for
        // opposite fixes — the first is this function's bug, the second means the
        // file really is trackless and the encoder wrote nothing. Without this
        // line the second case is diagnosed by the *absence* of the two above it,
        // which is not a thing anyone reads a log for.
        NSLog(@"FQVE: %@ loaded its tracks and has no %@ track",
              asset.URL.lastPathComponent, type);
    }
    return track;
}

/// This device's thermal state, on a scale both platforms share.
///
/// **The normalization is the point, not the reading.** Apple reports four
/// states and no headroom; Android reports seven and a float. A `PERF` row
/// carrying `thermal=fair` beside one carrying `thermal=MODERATE(2)/0.88` is
/// two rows that still cannot be compared — which is the exact failure a
/// thermal column exists to prevent, reappearing one level up. So `level` is
/// 0..3 and means the same thing on both, and `name` keeps the platform's own
/// word for a human reading a log.
///
/// `headroom` is deliberately **absent** here rather than invented: Android can
/// forecast how close it is to the threshold and Apple cannot, and a gauge
/// showing a made-up number cannot be unseen. Same shape as `Relief.none`
/// against an optional library — the absence is named rather than faked.
///
/// Serious is where a rider is told, matching Android's MODERATE. Fair happens
/// under any sustained load, and warning about it would only teach people to
/// ignore the warning.
static NSDictionary *currentThermalState(void) {
    NSProcessInfoThermalState state = [[NSProcessInfo processInfo] thermalState];
    int level;
    NSString *name;
    switch (state) {
        case NSProcessInfoThermalStateNominal:  level = 0; name = @"nominal";  break;
        case NSProcessInfoThermalStateFair:     level = 1; name = @"fair";     break;
        case NSProcessInfoThermalStateSerious:  level = 2; name = @"serious";  break;
        case NSProcessInfoThermalStateCritical: level = 3; name = @"critical"; break;
        default:                                level = -1; name = @"unknown"; break;
    }
    return @{
        @"level" : @(level),
        @"name" : name,
        @"throttling" : @(level >= 2),
    };
}

// Defined below, used in `appendVideoFrame` above it.
static void blendClipIntoFrame(uint8_t *dst, int frameWidth, int frameHeight,
                               CVPixelBufferRef clip, const FQVEClipColor *color,
                               int rx, int ry, int rw, int rh, int quarterTurns);

/// One clip, decoded forward, holding the frame that is currently on screen.
///
/// **Rec.709 is asked for, not assumed.** A modern action cam or drone writes
/// BT.2020 with an HLG transfer, and `kCVPixelFormatType_32BGRA` alone hands
/// those bytes back untouched — the buffer arrives tagged `ITU_R_2100_HLG` and
/// blending it into an sRGB frame produces a washed-out, flat picture with no
/// error anywhere. `AVVideoColorPropertiesKey` moves the tone map into the
/// reader, on hardware, for no measurable cost.
@interface FQVEClipReader : NSObject
@property(nonatomic) AVAssetReader *reader;
@property(nonatomic) AVAssetReaderTrackOutput *output;
@property(nonatomic) CVPixelBufferRef current;
@property(nonatomic) CMTime currentEnd;
/// The instant the last `seekableFrameAtTime:` was asked for, or invalid.
///
/// **What "backwards" is measured against, and it is deliberately not the held
/// frame's own start.** Sample durations do not always tile: measured on a
/// 120 fps drone clip, `start + duration` lands 0.7 ms short of the next
/// sample's timestamp, so the frame covering 30.000 s reports a start of
/// 30.0007 s. Asking for 30.000 s again then looks like a scrub backwards and
/// rebuilds the reader — 79 ms, measured, to answer with the frame it was
/// already holding. The question a seek actually has to answer is whether the
/// *caller* went back, which is this.
@property(nonatomic) CMTime lastAnswered;
/// How to read this clip's samples. Owned here, freed in `dealloc`.
@property(nonatomic, assign) FQVEClipColor *color;
/// The asset and its video track, kept so a seek can rebuild the reader without
/// paying `FQVEFirstTrack`'s load again — which is most of what opening a clip
/// costs. **The asset is retained explicitly**: `AVAssetTrack.asset` is a *weak*
/// reference, so a track outliving the local that created its asset holds
/// nothing, and the rebuild would find nil where a file used to be.
@property(nonatomic) AVURLAsset *asset;
@property(nonatomic) AVAssetTrack *track;
- (instancetype)initWithPath:(NSString *)path reason:(NSString **)reason;
- (CVPixelBufferRef)seekableFrameAtTime:(CMTime)time;
@end

/// What the track says its colour is, mapped onto the shared enums.
///
/// Read from the format description rather than assumed. A file that says
/// nothing gets Rec.709 above standard definition and Rec.601 below it, which is
/// what every player assumes — the same fallback, and the same reasoning, as the
/// Android side. Guessing is unavoidable; guessing silently is not.
static FQVEClipColor *FQVEClipColorFromTrack(AVAssetTrack *track) {
    FQVEStandard standard = FQVEStandardBT709;
    FQVETransfer transfer = FQVETransferSDR;

    CMFormatDescriptionRef desc =
        (__bridge CMFormatDescriptionRef)track.formatDescriptions.firstObject;
    if (desc) {
        CFStringRef matrix = CMFormatDescriptionGetExtension(
            desc, kCMFormatDescriptionExtension_YCbCrMatrix);
        if (matrix) {
            if (CFEqual(matrix, kCMFormatDescriptionYCbCrMatrix_ITU_R_2020)) {
                standard = FQVEStandardBT2020;
            } else if (CFEqual(matrix, kCMFormatDescriptionYCbCrMatrix_ITU_R_601_4)) {
                standard = FQVEStandardBT601;
            }
        } else {
            CGSize size = track.naturalSize;
            standard = size.height >= 720 ? FQVEStandardBT709 : FQVEStandardBT601;
        }

        CFStringRef fn = CMFormatDescriptionGetExtension(
            desc, kCMFormatDescriptionExtension_TransferFunction);
        if (fn) {
            if (CFEqual(fn, kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG)) {
                transfer = FQVETransferHLG;
            } else if (CFEqual(fn, kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ)) {
                transfer = FQVETransferPQ;
            }
        }
    }

    // `420YpCbCr10BiPlanarVideoRange` is limited range by its own definition,
    // which is why that is asked for by name rather than inferred.
    FQVEClipColor *color = FQVEClipColorCreate(standard, transfer, NO);
    NSLog(@"FQVE: CLIP color %@", FQVEClipColorDescribe(color));
    return color;
}

/// How far before a seek's target the rebuilt reader starts. See
/// `startReadingFrom:reason:` for why it is not zero.
static const int64_t kFQVEPreRollUs = 250000;

/// How far ahead a scrub may ask before rebuilding beats decoding through.
///
/// The same two seconds, and the same reasoning, as the Android reader's
/// `SEEK_AHEAD_US`: past this a rebuild is cheaper than the frames in between,
/// and below it ordinary forward nudging of a trim handle never pays for one.
/// Keeping the two platforms on one number is what lets a scrub feel the same on
/// both, and lets a disagreement between them be a bug rather than a setting.
static const int64_t kFQVESeekAheadUs = 2000000;

@implementation FQVEClipReader

- (instancetype)initWithPath:(NSString *)path {
    return [self initWithPath:path reason:NULL];
}

/// As above, but says which step refused.
///
/// **The decodability check has to open a clip the same way the render does.**
/// A checker that reimplements these steps can answer "fine" for a file the
/// real reader then refuses, and the render's own behaviour for a refused clip
/// is to leave the rectangle as the painter cleared it — so the disagreement
/// ships as a black window in a published film rather than as an error. One
/// initialiser with an optional reason, rather than two openers that can drift.
- (instancetype)initWithPath:(NSString *)path reason:(NSString **)reason {
    self = [super init];
    if (!self) { return nil; }

    self.asset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:path] options:nil];
    self.track = FQVEFirstTrack(self.asset, AVMediaTypeVideo);
    if (!self.track) {
        if (reason) { *reason = @"no video track, or its tracks did not load"; }
        return nil;
    }
    self.color = FQVEClipColorFromTrack(self.track);

    if (![self startReadingFrom:kCMTimeZero reason:reason]) {
        return nil;
    }
    return self;
}

/// Builds the reader and its output, reading from [time] onward.
///
/// **One place, called by the open and by every seek.** The output settings are
/// the export's contract with `FQVEClipColor` — ten-bit YCbCr, no
/// `AVVideoColorPropertiesKey`, nobody else's tone map — and a preview that
/// rebuilt the reader with its own copy of them would be one edit away from
/// showing a rider a frame the film will not draw. Written twice, they drift;
/// written once, the preview cannot disagree with the export about color even in
/// principle.
- (BOOL)startReadingFrom:(CMTime)time reason:(NSString **)reason {
    NSError *error = nil;
    // **Named on `self`, not just captured locally.** A reader that outlives
    // only its output deallocates when the method returns, and
    // `copyNextSampleBuffer` then raises from the writer's own dispatch queue,
    // where no `@try` reaches it and the app dies rather than returning an
    // error. That exact bug has been paid for once in this file already.
    self.reader = [[AVAssetReader alloc] initWithAsset:self.asset error:&error];
    if (!self.reader) {
        if (reason) {
            *reason = error.localizedDescription ?: @"could not open a reader";
        }
        return NO;
    }

    // **Ten-bit YCbCr, and deliberately no `AVVideoColorPropertiesKey`.**
    // Naming Rec.709 there asked AVFoundation to convert, and it obliged with a
    // tone map we could not see, match or reproduce — which is why the same clip
    // came out of an iPhone looking different from the same clip out of a
    // Galaxy. Taking the samples as the file stores them and converting in
    // `FQVEClipColor` is what lets both platforms run one arithmetic against one
    // published method. It is also the same shape Android receives, so the two
    // are comparable pixel for pixel rather than by eye.
    self.output = [[AVAssetReaderTrackOutput alloc]
        initWithTrack:self.track
       outputSettings:@{
           (id)kCVPixelBufferPixelFormatTypeKey :
               @(kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange),
       }];
    self.output.alwaysCopiesSampleData = NO;
    if (![self.reader canAddOutput:self.output]) {
        if (reason) { *reason = @"this device cannot decode it to 10-bit YCbCr"; }
        return NO;
    }
    [self.reader addOutput:self.output];

    // **Started a little before the wanted instant, not at it.** An
    // `AVAssetReader` given a time range delivers the samples that fall inside
    // it, and the frame a film shows at *t* is the one that *began* before *t*
    // and is still on screen — so starting exactly at *t* hands back the next
    // frame instead, which is the wrong picture by up to a frame and looks
    // exactly like the right one. A quarter of a second is a handful of frames
    // at any rate anyone shoots at, and the walk in `frameAtTime:` throws them
    // away for a cost that does not show up next to the decode.
    if (CMTIME_COMPARE_INLINE(time, >, kCMTimeZero)) {
        CMTime from = CMTimeSubtract(time, CMTimeMake(kFQVEPreRollUs, 1000000));
        if (CMTIME_COMPARE_INLINE(from, <, kCMTimeZero)) { from = kCMTimeZero; }
        self.reader.timeRange = CMTimeRangeMake(from, kCMTimePositiveInfinity);
    }

    if (![self.reader startReading]) {
        if (reason) {
            *reason = self.reader.error.localizedDescription ?: @"could not start reading";
        }
        return NO;
    }

    // The held frame belongs to where the reader used to be. Keeping it would
    // let a seek that decodes nothing answer with a picture from another
    // instant — which is a plausible frame, and therefore the failure this
    // whole feature would ship with unnoticed.
    if (self.current) {
        CVPixelBufferRelease(self.current);
        self.current = NULL;
    }
    // Invalid rather than zero: nothing has been answered from this reader yet,
    // and zero would read as "the caller last asked for the first instant",
    // which makes every subsequent request look like a step forward.
    self.lastAnswered = kCMTimeInvalid;
    self.currentEnd = kCMTimeZero;
    return YES;
}

/// The frame covering [time], decoded forward from wherever the reader is.
///
/// Holds the last sample rather than decoding one per call, because several
/// output frames land inside one source frame whenever the film runs slower
/// than the clip — and because a source that has run out should keep showing
/// its final frame rather than vanish.
- (CVPixelBufferRef)frameAtTime:(CMTime)time {
    while (CMTIME_COMPARE_INLINE(self.currentEnd, <=, time)) {
        CMSampleBufferRef sample = [self.output copyNextSampleBuffer];
        if (!sample) { break; }
        CVPixelBufferRef image = CMSampleBufferGetImageBuffer(sample);
        if (image) {
            CVPixelBufferRetain(image);
            if (self.current) { CVPixelBufferRelease(self.current); }
            self.current = image;
            CMTime start = CMSampleBufferGetPresentationTimeStamp(sample);
            CMTime dur = CMSampleBufferGetDuration(sample);
            self.currentEnd = CMTIME_IS_NUMERIC(dur) ? CMTimeAdd(start, dur)
                                                     : CMTimeAdd(start, CMTimeMake(1, 120));
        }
        CFRelease(sample);
    }
    return self.current;
}

/// As `frameAtTime:`, but able to go backwards and to jump.
///
/// **The export never calls this and the preview always does.** A film plays
/// each clip forward exactly once, so `frameAtTime:`'s sequential walk is the
/// right shape for it and rebuilding a reader mid-export would be pure loss. A
/// trim handle is dragged in both directions and dropped anywhere in the file,
/// where the same walk cannot go back at all and would decode a minute of 4K to
/// go forward. So the seek lives here, on top of the same reader rather than
/// beside it: the frame this returns is chosen by the identical forward walk,
/// simply started closer to the answer.
///
/// The forward test is skipped once the reader has run out, mirroring the
/// Android side — a handle held past the end of a clip would otherwise rebuild
/// on every call, each one landing on the same last frame.
- (CVPixelBufferRef)seekableFrameAtTime:(CMTime)time {
    BOOL exhausted = self.reader.status == AVAssetReaderStatusCompleted;
    BOOL behind = CMTIME_IS_VALID(self.lastAnswered)
        && CMTIME_COMPARE_INLINE(time, <, self.lastAnswered);
    BOOL farAhead = !exhausted
        && CMTIME_COMPARE_INLINE(
               time, >, CMTimeAdd(self.currentEnd,
                                  CMTimeMake(kFQVESeekAheadUs, 1000000)));
    if (behind || farAhead) {
        NSString *reason = nil;
        if (![self startReadingFrom:time reason:&reason]) {
            NSLog(@"FQVE: CLIP could not seek to %lldus: %@",
                  (long long)(CMTimeGetSeconds(time) * 1e6), reason);
            return NULL;
        }
    }
    CVPixelBufferRef frame = [self frameAtTime:time];
    if (frame) {
        self.lastAnswered = time;
    }
    return frame;
}

- (void)dealloc {
    if (self.current) { CVPixelBufferRelease(self.current); }
    [self.reader cancelReading];
    FQVEClipColorRelease(self.color);
}

@end

/// One edge of a frame, scaled so that [longest] lands on [maxEdge].
static int FQVEScaledEdge(int edge, int longest, int maxEdge) {
    if (longest <= maxEdge) { return edge; }
    // Rounded rather than truncated, so a 16:9 frame stays as close to 16:9 as
    // integers allow — and floored at one, because a frame far wider than it is
    // tall would otherwise scale its short edge to nothing and hand back an
    // empty buffer with a plausible width. The Java `ClipPreview.scaled` is the
    // same two lines; where they differ, they are wrong.
    long scaled = llround((double)edge * maxEdge / longest);
    return (int)MAX(1L, scaled);
}

/// [clip] as straight RGBA, downsampled so its longer side fits [maxEdge].
///
/// **This is `blendClipIntoFrame` into a cleared rectangle, and nothing else.**
/// Same nearest-neighbor tap, same half-pixel offset, same `FQVEClipColorToRgb`,
/// same ten bits in the high bits of each word. A preview exists to promise what
/// the export will draw, so anything cheaper here — a different sampler, a
/// different tone map, AVFoundation's own BGRA conversion — is a rider trimming
/// against a picture the film will not show.
///
/// The rotation the container carries is deliberately *not* applied: the export
/// turns the frame in the hole's `quarterTurns` rather than in the reader, so
/// turning it here would mean the preview and the film disagree about which way
/// up the footage is by exactly the metadata that was supposed to settle it.
static NSData *FQVEClipPreviewRgba(CVPixelBufferRef clip,
                                   const FQVEClipColor *color,
                                   int maxEdge,
                                   int *outWidth,
                                   int *outHeight)
{
    if (!clip || !color || maxEdge <= 0) { return nil; }

    CVPixelBufferLockBaseAddress(clip, kCVPixelBufferLock_ReadOnly);
    const uint16_t *lumaPlane =
        (const uint16_t *)CVPixelBufferGetBaseAddressOfPlane(clip, 0);
    const uint16_t *chromaPlane =
        (const uint16_t *)CVPixelBufferGetBaseAddressOfPlane(clip, 1);
    size_t lumaStride = CVPixelBufferGetBytesPerRowOfPlane(clip, 0);
    size_t chromaStride = CVPixelBufferGetBytesPerRowOfPlane(clip, 1);
    int srcW = (int)CVPixelBufferGetWidth(clip);
    int srcH = (int)CVPixelBufferGetHeight(clip);
    if (!lumaPlane || !chromaPlane || srcW <= 0 || srcH <= 0) {
        CVPixelBufferUnlockBaseAddress(clip, kCVPixelBufferLock_ReadOnly);
        return nil;
    }

    int longest = MAX(srcW, srcH);
    int outW = FQVEScaledEdge(srcW, longest, maxEdge);
    int outH = FQVEScaledEdge(srcH, longest, maxEdge);

    NSMutableData *out = [NSMutableData dataWithLength:(NSUInteger)outW * outH * 4];
    uint8_t *dst = (uint8_t *)out.mutableBytes;

    for (int y = 0; y < outH; y++) {
        float v = (y + 0.5f) / outH;
        int sy = (int)(v * srcH);
        if (sy < 0) { sy = 0; } else if (sy >= srcH) { sy = srcH - 1; }

        const uint16_t *lumaRow =
            (const uint16_t *)((const uint8_t *)lumaPlane + (size_t)sy * lumaStride);
        const uint16_t *chromaRow =
            (const uint16_t *)((const uint8_t *)chromaPlane
                               + (size_t)(sy / 2) * chromaStride);

        for (int x = 0; x < outW; x++) {
            float u = (x + 0.5f) / outW;
            int sx = (int)(u * srcW);
            if (sx < 0) { sx = 0; } else if (sx >= srcW) { sx = srcW - 1; }

            const int yy = lumaRow[sx] >> 6;
            const int cb = chromaRow[(sx / 2) * 2] >> 6;
            const int cr = chromaRow[(sx / 2) * 2 + 1] >> 6;
            const uint32_t rgb = FQVEClipColorToRgb(color, yy, cb, cr);

            uint8_t *d = dst + ((size_t)y * outW + x) * 4;
            d[0] = (uint8_t)((rgb >> 16) & 0xFF);
            d[1] = (uint8_t)((rgb >> 8) & 0xFF);
            d[2] = (uint8_t)(rgb & 0xFF);
            d[3] = 255;
        }
    }

    CVPixelBufferUnlockBaseAddress(clip, kCVPixelBufferLock_ReadOnly);
    if (outWidth) { *outWidth = outW; }
    if (outHeight) { *outHeight = outH; }
    return out;
}

@interface FQVEClipPreviewEntry : NSObject
@property(nonatomic) FQVEClipReader *reader;
@property(nonatomic) NSTimeInterval lastUsed;
@end

@implementation FQVEClipPreviewEntry
@end

/// Decoders held open so a trim handle can be dragged.
///
/// The plugin's `mClipReaders` is the same idea for the export and this is
/// deliberately not that dictionary. The two read the same files in opposite
/// patterns — a film walks each clip forward exactly once, a rider scrubs one
/// clip back and forth — so a shared cache would leave the export's reader
/// parked wherever the last scrub stopped, and the next frame of the film would
/// pay for a rebuild nothing asked for.
///
/// **Every reader is touched from one serial queue and only from there.** A cold
/// open of a 4K HEVC clip is a couple of hundred milliseconds and the platform
/// thread is the thread the handle is drawn on, so decoding there makes the
/// gesture being previewed stutter. A serial queue rather than a concurrent one,
/// because it also serializes access to the readers for free: there are no locks
/// in here and there is nothing to get wrong.
@interface FQVEClipPreviews : NSObject
- (void)frameAtPath:(NSString *)path
             timeUs:(int64_t)timeUs
            maxEdge:(int)maxEdge
         completion:(void (^)(NSData *rgba, int width, int height))completion;
/// Closes every reader, and does not return until they are closed.
- (void)releaseReaders;
@end

@implementation FQVEClipPreviews {
    dispatch_queue_t _queue;
    NSMutableDictionary<NSString *, FQVEClipPreviewEntry *> *_readers;
}

/// How many decoders may be open at once.
///
/// The export's cache on this platform is unbounded, on the grounds that
/// AVFoundation readers are cheap. A preview's are not cheap in the same way: a
/// screen can sit open for minutes holding hardware an export is about to want,
/// where an export's readers go away with the film. Two, matching the Android
/// side, so that flicking between two clips does not reopen either.
static const NSUInteger kFQVEMaxPreviewReaders = 2;

/// How long a reader may sit unused before it gives its decode session back.
///
/// A backstop rather than the mechanism — `releaseClipPreviews` is what the
/// screen is supposed to call. This is what covers the screen that forgot, and
/// the rider who put the phone down mid-drag.
static const NSTimeInterval kFQVEPreviewIdleSeconds = 20.0;

- (instancetype)init {
    self = [super init];
    if (self) {
        _queue = dispatch_queue_create("fqve.clip.preview", DISPATCH_QUEUE_SERIAL);
        _readers = [NSMutableDictionary dictionary];
    }
    return self;
}

- (void)frameAtPath:(NSString *)path
             timeUs:(int64_t)timeUs
            maxEdge:(int)maxEdge
         completion:(void (^)(NSData *, int, int))completion
{
    dispatch_async(_queue, ^{
        NSData *rgba = nil;
        int width = 0;
        int height = 0;

        FQVEClipReader *reader = [self readerFor:path];
        if (reader) {
            CVPixelBufferRef frame =
                [reader seekableFrameAtTime:CMTimeMake(timeUs, 1000000)];
            rgba = FQVEClipPreviewRgba(frame, reader.color, maxEdge, &width, &height);
            if (!rgba) {
                // The reader is dropped rather than kept. A decode that produced
                // nothing leaves it at an instant nobody can name, and reusing it
                // would answer the next scrub with a frame from wherever it
                // stopped — which looks like a picture rather than like an error.
                [self forget:path];
            }
        }

        completion(rgba, width, height);
        [self sweepLater];
    });
}

- (void)releaseReaders {
    // Blocking is the point. The two callers are the screen going away and an
    // export setting up, and an export that begins while a decode session is
    // still being handed back is the failure this exists to prevent.
    dispatch_sync(_queue, ^{
        [self->_readers removeAllObjects];
    });
}

// ---- preview queue only ----------------------------------------------------

- (FQVEClipReader *)readerFor:(NSString *)path {
    NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];
    FQVEClipPreviewEntry *existing = _readers[path];
    if (existing) {
        existing.lastUsed = now;
        return existing.reader;
    }
    while (_readers.count >= kFQVEMaxPreviewReaders) {
        [self evictOldest];
    }
    NSString *reason = nil;
    FQVEClipReader *opened = [[FQVEClipReader alloc] initWithPath:path reason:&reason];
    if (!opened) {
        NSLog(@"FQVE: CLIP preview cannot open %@: %@", path,
              reason ?: @"could not be opened");
        return nil;
    }
    FQVEClipPreviewEntry *entry = [[FQVEClipPreviewEntry alloc] init];
    entry.reader = opened;
    entry.lastUsed = now;
    _readers[path] = entry;
    return opened;
}

- (void)forget:(NSString *)path {
    [_readers removeObjectForKey:path];
}

/// Asks the queue to look again once the idle window has passed.
///
/// Scheduled rather than checked on the next call, because there may not be a
/// next call — a rider who stops scrubbing and puts the phone down is exactly
/// the case where a session would otherwise be held indefinitely, and it is also
/// the case nobody notices until some other clip will not open.
- (void)sweepLater {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,
                                 (int64_t)((kFQVEPreviewIdleSeconds + 0.5) * NSEC_PER_SEC)),
                   _queue, ^{ [self sweep]; });
}

- (void)sweep {
    NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];
    for (NSString *path in [_readers allKeys]) {
        if (now - _readers[path].lastUsed >= kFQVEPreviewIdleSeconds) {
            NSLog(@"FQVE: CLIP preview evict idle %@", path);
            [_readers removeObjectForKey:path];
        }
    }
}

- (void)evictOldest {
    NSString *oldestKey = nil;
    NSTimeInterval oldest = INFINITY;
    for (NSString *path in _readers) {
        if (_readers[path].lastUsed < oldest) {
            oldest = _readers[path].lastUsed;
            oldestKey = path;
        }
    }
    if (!oldestKey) { return; }
    NSLog(@"FQVE: CLIP preview evict oldest %@ to stay under %lu readers",
          oldestKey, (unsigned long)kFQVEMaxPreviewReaders);
    [_readers removeObjectForKey:oldestKey];
}

@end

typedef NS_ENUM(NSUInteger, LogLevel) {
    none = 0,
    error = 1,
    standard = 2,
    verbose = 3,
};

@interface FlutterQuickVideoEncoderPlugin () <FlutterStreamHandler>
@property(nonatomic) NSObject<FlutterPluginRegistrar> *registrar;
@property(nonatomic) FlutterMethodChannel *mMethodChannel;
@property(nonatomic) FlutterEventChannel *mThermalChannel;
@property(nonatomic, copy) FlutterEventSink mThermalSink;
@property(nonatomic) LogLevel mLogLevel;
@property(nonatomic) AVAssetWriter *mAssetWriter;
@property(nonatomic) AVAssetWriterInput *mAudioInput;
@property(nonatomic) AVAssetWriterInput *mVideoInput;
@property(nonatomic) int videoFrameIdx;
@property(nonatomic) int audioFrameIdx;
@property(nonatomic) int width;
@property(nonatomic) int height;
@property(nonatomic) int fps;
@property(nonatomic) int audioChannels;

/// One sequential clip reader per source path, keyed by path.
///
/// **Sequential, never seeking.** A clip plays forward, so the reader walks the
/// file the way it was written and every frame costs one `copyNextSampleBuffer`.
/// Seeking per frame would re-open a decode session for each of them, which on
/// a 4K HEVC file is the difference between three milliseconds and forty.
@property(nonatomic) NSMutableDictionary<NSString *, FQVEClipReader *> *mClipReaders;

/// Decoders held open for a trim handle, or nil until one is asked for.
///
/// Separate from `mClipReaders` on purpose, and released whenever an export sets
/// up, so a screen that forgot to let go cannot starve the film of a decoder.
@property(nonatomic) FQVEClipPreviews *mClipPreviews;

/// The last thermal level reported, so only transitions are logged. Starts nil,
/// which no level equals, so the first frame always states where it began.
@property(nonatomic) NSNumber *mThermalLevel;
@property(nonatomic) NSString *mThermalName;
@property(nonatomic) int sampleRate;
@end

@implementation FlutterQuickVideoEncoderPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar
{
    // method channel
    FlutterMethodChannel *methodChannel =
        [FlutterMethodChannel methodChannelWithName:@"flutter_quick_video_encoder/methods"
                                    binaryMessenger:[registrar messenger]];

    // instance
    FlutterQuickVideoEncoderPlugin *instance = [[FlutterQuickVideoEncoderPlugin alloc] init];
    instance.mMethodChannel = methodChannel;
    instance.mLogLevel = verbose;

    [registrar addMethodCallDelegate:instance channel:methodChannel];

    // **Pushed, not polled, and one source for two consumers.** The perf row
    // wants transitions to build a trace; the Export gauge wants the current
    // rung to draw a thermometer. Asking twice invites the two to disagree
    // about the same device at the same moment.
    //
    // Same channel name and same payload as the Android side, because a row
    // from an iPhone and a row from a Galaxy have to be comparable — that is
    // the whole reason `level` is normalized rather than each platform's own
    // enum.
    instance.mThermalChannel =
        [FlutterEventChannel eventChannelWithName:@"flutter_quick_video_encoder/thermal"
                                  binaryMessenger:[registrar messenger]];
    [instance.mThermalChannel setStreamHandler:instance];
}

- (FlutterError *)onListenWithArguments:(id)arguments eventSink:(FlutterEventSink)events {
    self.mThermalSink = events;
    [[NSNotificationCenter defaultCenter]
        addObserver:self
           selector:@selector(thermalStateChanged:)
               name:NSProcessInfoThermalStateDidChangeNotification
             object:nil];
    // Immediately, because a subscriber that arrives mid-render would otherwise
    // wait for the next transition to learn anything — and a device that has
    // already reached its ceiling may never have another one.
    events(currentThermalState());
    return nil;
}

- (FlutterError *)onCancelWithArguments:(id)arguments {
    [[NSNotificationCenter defaultCenter]
        removeObserver:self
                  name:NSProcessInfoThermalStateDidChangeNotification
                object:nil];
    self.mThermalSink = nil;
    return nil;
}

- (void)thermalStateChanged:(NSNotification *)notification {
    FlutterEventSink sink = self.mThermalSink;
    if (sink == nil) {
        return;
    }
    NSDictionary *thermal = currentThermalState();
    // The notification arrives on an arbitrary queue and a sink is not
    // thread-safe, so hop deliberately rather than hoping they coincide.
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.mThermalSink != nil) {
            self.mThermalSink(thermal);
        }
    });
    // No log here. The per-frame check in `appendVideoFrame` already prints
    // transitions with the frame they happened on, and it does so whether or
    // not anything is subscribed — which is what a bench run needs. Logging in
    // both places would print every transition twice and teach people to skim
    // the line.
}

- (void)handleMethodCall:(FlutterMethodCall *)call result:(FlutterResult)result
{
    @try
    {
        if (self.mLogLevel >= standard) {
            NSLog(@"handleMethodCall: %@", call.method);
        }

        if ([@"thermalStatus" isEqualToString:call.method])
        {
            result(currentThermalState());
        }
        else if ([@"checkClipsDecodable" isEqualToString:call.method])
        {
            // Asked *before* a render rather than discovered inside one. A
            // refusal that arrives four thousand frames in is a refusal that
            // arrives after the waiting.
            //
            // Answers with a map of path to reason, empty when every clip
            // opened and produced a frame. Named paths rather than a count,
            // because the message a rider needs is which of *their* files this
            // device cannot read.
            //
            // Implemented on Apple as well as Android even though AVFoundation
            // refuses far less often, because a check that exists on one
            // platform makes "every clip is fine" and "nobody asked" the same
            // answer everywhere else — which is the shape of failure this call
            // was added to remove.
            NSDictionary *args = (NSDictionary *)call.arguments;
            NSArray *paths = args[@"paths"];
            NSMutableDictionary<NSString *, NSString *> *failures =
                [NSMutableDictionary dictionary];
            for (id item in (paths ?: @[])) {
                if (![item isKindOfClass:[NSString class]]) { continue; }
                NSString *path = (NSString *)item;
                NSString *reason = nil;
                FQVEClipReader *probe = [[FQVEClipReader alloc] initWithPath:path
                                                                      reason:&reason];
                if (!probe) {
                    failures[path] = reason ?: @"could not be opened";
                } else if (![probe frameAtTime:kCMTimeZero]) {
                    // Opening is not reading. A file whose header parses and
                    // whose first sample does not decode is exactly the case
                    // that would otherwise reach the compositor and encode as a
                    // black window with nothing logged.
                    failures[path] = @"opened but decoded no frame";
                }
                if (failures[path]) {
                    NSLog(@"FQVE: CLIP cannot decode %@: %@", path, failures[path]);
                }
            }
            result(failures);
        }
        else if ([@"clipFrameAt" isEqualToString:call.method])
        {
            // One decode per scrub position, through the reader the export uses.
            // A general-purpose player would be easier and is the wrong answer:
            // the rider is choosing a trim against this picture, so it has to be
            // the picture the film will show, down to the color conversion.
            NSDictionary *args = (NSDictionary *)call.arguments;
            NSString *path = args[@"path"];
            NSNumber *atUs = args[@"atUs"];
            NSNumber *maxEdge = args[@"maxEdge"];
            if (![path isKindOfClass:[NSString class]] || !atUs || !maxEdge) {
                result([FlutterError errorWithCode:@"clipFrameAt"
                                           message:@"path, atUs and maxEdge are all required"
                                           details:nil]);
                return;
            }
            if (!self.mClipPreviews) {
                self.mClipPreviews = [[FQVEClipPreviews alloc] init];
            }
            // Answered from the preview queue, so the platform thread is free to
            // keep drawing the handle that asked. A `FlutterResult` belongs to
            // the platform thread, hence the hop back.
            [self.mClipPreviews frameAtPath:path
                                     timeUs:atUs.longLongValue
                                    maxEdge:maxEdge.intValue
                                 completion:^(NSData *rgba, int width, int height) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!rgba) {
                        result(nil);
                        return;
                    }
                    result(@{
                        @"rgba" : [FlutterStandardTypedData typedDataWithBytes:rgba],
                        @"width" : @(width),
                        @"height" : @(height),
                    });
                });
            }];
        }
        else if ([@"releaseClipPreviews" isEqualToString:call.method])
        {
            [self.mClipPreviews releaseReaders];
            result(nil);
        }
        else if ([@"setLogLevel" isEqualToString:call.method])
        {
            NSDictionary *args = (NSDictionary*)call.arguments;
            NSNumber *nLogLevel  = args[@"log_level"];

            self.mLogLevel = (LogLevel) nLogLevel.integerValue;

            result(@(true));
        }
        else if ([@"setup" isEqualToString:call.method])
        {
            // Let the preview decoders go before the film asks for any. A device
            // has a finite supply of concurrent decode sessions, so a Clips
            // screen still holding one is a clip this export may not be able to
            // open — and that surfaces as an unrelated file failing, which is the
            // wrong diagnosis every time. Cheap: nothing is scrubbing during an
            // export, so nothing reopens.
            [self.mClipPreviews releaseReaders];

            NSDictionary *args = (NSDictionary*)call.arguments;

            // Extract parameters from 'args'
            NSNumber *nWidth =         args[@"width"];
            NSNumber *nHeight =        args[@"height"];
            NSNumber *nFps =           args[@"fps"];
            NSNumber *nVideoBitrate =  args[@"videoBitrate"];
            NSNumber *nAudioChannels = args[@"audioChannels"];
            NSNumber *nAudioBitrate =  args[@"audioBitrate"];
            NSNumber *nSampleRate =    args[@"sampleRate"];
            NSString *nProfileLevel =  args[@"profileLevel"];
            NSString *filepath =       args[@"filepath"];

            // remember these
            self.width =         (int) nWidth.integerValue;
            self.height =        (int) nHeight.integerValue;
            self.fps =           (int) nFps.integerValue;
            self.audioChannels = (int) nAudioChannels.integerValue;
            self.sampleRate =    (int) nSampleRate.integerValue;

            // reset counters
            self.videoFrameIdx = 0;
            self.audioFrameIdx = 0;

            NSError *error = nil;
    
            // Output file URL
            NSURL *fileURL = [NSURL fileURLWithPath:filepath];

            // Check if file already exists at URL, we must delete it
            if ([[NSFileManager defaultManager] fileExistsAtPath:[fileURL path]]) {
                [[NSFileManager defaultManager] removeItemAtURL:fileURL error:&error];
                if (error) {
                    result([FlutterError errorWithCode:@"FileRemoveError" 
                                            message:@"Unable to remove existing file" 
                                            details:[error localizedDescription]]);
                    return;
                }
            }

            // Initialize AVAssetWriter with the file URL
            self.mAssetWriter = [[AVAssetWriter alloc] initWithURL:fileURL
                                                         fileType:AVFileTypeQuickTimeMovie
                                                            error:&error];
            
            if (error) {
                result([FlutterError errorWithCode:@"AVAssetWriterInitializationError" 
                                           message:[error localizedDescription] 
                                           details:nil]);
                return;
            }

            // setup video?
            if (self.width != 0 && self.height != 0) {

                // Video compression settings
                NSMutableDictionary *compressionProperties = [NSMutableDictionary dictionaryWithDictionary:@{
                    AVVideoAverageBitRateKey : @(nVideoBitrate.integerValue)
                }];

                // Add profile level only if it's not 'any'
                if (![nProfileLevel isEqualToString:@"any"]) {
                    NSString *profileLevelValue = [self parseProfileLevel:nProfileLevel];
                    [compressionProperties setObject:profileLevelValue forKey:AVVideoProfileLevelKey];
                }

                // Video settings
                NSDictionary *videoSettings = @{
                    AVVideoCodecKey : AVVideoCodecTypeH264,
                    AVVideoWidthKey : @(self.width),
                    AVVideoHeightKey : @(self.height),
                    AVVideoCompressionPropertiesKey : compressionProperties
                };

                // Initialize video input
                self.mVideoInput = [[AVAssetWriterInput alloc] initWithMediaType:AVMediaTypeVideo
                                                                outputSettings:videoSettings];
                // NO, not YES. YES tells AVFoundation the samples are arriving
                // from a live source in real time, so it must not make the
                // caller wait — it drops frames rather than block. That is
                // right for a camera and wrong for an offline encode, where
                // every frame is rendered ahead of time and pushed as fast as
                // the writer will take it. With YES the appends outrun the
                // writer and it fails with "bad status" partway through.
                //
                // Only reproduces on a real device: the simulator's encoder is
                // fast enough to keep up. Upstream issue #13, which the
                // maintainer closed with "this package is actually no longer
                // maintained" — which is why this is a fork.
                self.mVideoInput.expectsMediaDataInRealTime = NO;

                // Add video input to asset writer
                if (![self.mAssetWriter canAddInput:self.mVideoInput]) {
                    result([FlutterError errorWithCode:@"VideoInputAdditionError" 
                                            message:@"Unable to add video input to AVAssetWriter" 
                                            details:nil]);
                    return;
                }

                [self.mAssetWriter addInput:self.mVideoInput];
            }

            // setup audio?
            if (self.audioChannels != 0 && self.sampleRate != 0) {

                // Audio settings
                NSDictionary* audioSettings = @{
                    AVFormatIDKey : @(kAudioFormatMPEG4AAC),
                    AVSampleRateKey : @(self.sampleRate),
                    AVNumberOfChannelsKey: @(nAudioChannels.integerValue),
                    AVEncoderBitRateKey: @(nAudioBitrate.integerValue)
                };

                // Initialize audio input
                self.mAudioInput = [[AVAssetWriterInput alloc] initWithMediaType:AVMediaTypeAudio
                                                                outputSettings:audioSettings];
                // Same reasoning as the video input above: this writer is fed
                // from a file, not from a microphone.
                self.mAudioInput.expectsMediaDataInRealTime = NO;

                // Add audio input to asset writer
                if (![self.mAssetWriter canAddInput:self.mAudioInput]) {
                    result([FlutterError errorWithCode:@"AudioInputAdditionError" 
                                            message:@"Unable to add audio input to AVAssetWriter" 
                                            details:nil]);
                    return;
                }
                [self.mAssetWriter addInput:self.mAudioInput];
            }

            // Check status
            if (self.mAssetWriter.status == AVAssetWriterStatusFailed) {
                NSError *error = self.mAssetWriter.error;
                result([FlutterError errorWithCode:@"AVAssetWriterStatus"
                                        message:@"Failed to initialize AVAssetWriter"
                                        details:[error localizedDescription]]);
                return;
            }

            result(@(true));
        }
        else if ([@"appendVideoFrame" isEqualToString:call.method])
        {
            NSDictionary *args = (NSDictionary*)call.arguments;
            FlutterStandardTypedData *rawRgbaData = args[@"rawRgba"];
            NSData *videoFrameData = rawRgbaData.data;
            if (!self.mClipReaders) {
                self.mClipReaders = [NSMutableDictionary dictionary];
            }

            // Say when the device starts throttling, so a render that slows
            // down has a reason attached rather than being a number nobody can
            // account for. Measured on Android: a film came back 11 ms/frame
            // slower than the same film and the difference was the phone's
            // temperature, not the code.
            //
            // On the transition rather than on a timer — the moment it changes
            // is the fact that explains the slowdown, and a periodic sample
            // would place it up to its own interval away from where it
            // happened. `thermalState` is a cheap property read here, unlike
            // Android's binder call, so per-frame costs nothing.
            // **`isEqual:`, not `isEqualToNumber:`.** The typed comparison
            // raises `NSInvalidArgumentException` on a nil argument, and
            // `mThermalLevel` is nil on the first frame by construction — that
            // is how the line knows to state where it began. The exception was
            // then caught by this method's own `@try`, returned to Dart as a
            // FlutterError, and the export stopped after exactly one frame with
            // nothing in the log but `handleMethodCall: appendVideoFrame`.
            //
            // It compiled, and it read correctly. Only running it said
            // otherwise, which is the whole argument for running it.
            NSDictionary *thermal = currentThermalState();
            if (![thermal[@"level"] isEqual:self.mThermalLevel]) {
                NSLog(@"THERMAL %@ frame=%d (was %@)",
                      thermal[@"name"], self.videoFrameIdx,
                      self.mThermalName ?: @"-");
                self.mThermalLevel = thermal[@"level"];
                self.mThermalName = thermal[@"name"];
            }

            // Check if the asset writer is initialized
            if (!self.mAssetWriter) {
                result([FlutterError errorWithCode:@"AssetWriterUnavailable"
                                        message:@"AVAssetWriter is not initialized"
                                        details:nil]);
                return;
            }

            // Check if video input is ready
            if (!self.mVideoInput) {
                result([FlutterError errorWithCode:@"AVAssetWriterInputUnavailable"
                                        message:@"AVAssetWriterInput is not initialized"
                                        details:nil]);
                return;
            }

            // Check status
            if (self.mAssetWriter.status == AVAssetWriterStatusFailed) {
                NSError *error = self.mAssetWriter.error;
                result([FlutterError errorWithCode:@"AVAssetWriterStatus"
                                        message:@"AVAssetWriter bad status"
                                        details:[error localizedDescription]]);
                return;
            }

            // Ensure that we have started the session
            if (self.mAssetWriter.status != AVAssetWriterStatusWriting) {
                [self.mAssetWriter startWriting];
                [self.mAssetWriter startSessionAtSourceTime:kCMTimeZero];
            }

            // Fill the caller's holes with video before anything is encoded.
            //
            // **A mutable copy, made now.** `videoFrameData` is the `NSData` off
            // a `FlutterStandardTypedData` and lives only for this call; the
            // blend writes into it, so it cannot be the caller's bytes. The copy
            // is also what keeps this safe if the runloop below ever re-enters.
            NSArray *holes = args[@"holes"];
            if (holes.count > 0) {
                NSMutableData *composited = [videoFrameData mutableCopy];
                uint8_t *dst = (uint8_t *)composited.mutableBytes;
                for (NSDictionary *hole in holes) {
                    NSString *path = hole[@"path"];
                    if (![path isKindOfClass:[NSString class]]) { continue; }

                    FQVEClipReader *reader = self.mClipReaders[path];
                    if (!reader) {
                        reader = [[FQVEClipReader alloc] initWithPath:path];
                        // A source that will not open leaves the rect as the
                        // painter left it — transparent — rather than failing
                        // the whole export. The frame is visibly wrong, which
                        // is the point: a clip that cannot be read should not
                        // look like a clip that is simply dark.
                        if (!reader) { continue; }
                        self.mClipReaders[path] = reader;
                    }

                    int64_t us = [hole[@"sourceTimeUs"] longLongValue];
                    CVPixelBufferRef clip =
                        [reader frameAtTime:CMTimeMake(us, 1000000)];

                    blendClipIntoFrame(dst, self.width, self.height, clip,
                                       reader.color,
                                       [hole[@"x"] intValue],
                                       [hole[@"y"] intValue],
                                       [hole[@"w"] intValue],
                                       [hole[@"h"] intValue],
                                       [hole[@"quarterTurns"] intValue]);
                }
                videoFrameData = composited;
            }

            // Create video sample buffer from the provided data
            CMSampleBufferRef sampleBuffer = createVideoSampleBuffer(
                self.fps, self.videoFrameIdx, self.width, self.height, videoFrameData);

            if (!sampleBuffer) {
                result([FlutterError errorWithCode:@"SampleBufferCreationFailed"
                                        message:@"Failed to create video sample buffer"
                                        details:nil]);
                return;
            }

            // wait until ready
            while (self.mVideoInput.readyForMoreMediaData == FALSE) {
                NSDate *maxDate = [NSDate dateWithTimeIntervalSinceNow:0.1];
                [[NSRunLoop currentRunLoop] runUntilDate:maxDate];
            }

            // wait until ready
            while (CMSampleBufferDataIsReady(sampleBuffer) == FALSE) {
                NSDate *maxDate = [NSDate dateWithTimeIntervalSinceNow:0.1];
                [[NSRunLoop currentRunLoop] runUntilDate:maxDate];
            }

            // Append the sample buffer
            if (![self.mVideoInput appendSampleBuffer:sampleBuffer]) {
                NSError *error = self.mAssetWriter.error;
                NSString *errorDetails = error ? [error localizedDescription] : @"Unknown error";
                result([FlutterError errorWithCode:@"SampleBufferAppendFailed"
                                        message:@"Failed to append video sample buffer"
                                        details:errorDetails]);
                CFRelease(sampleBuffer);
                return;
            }

            // Release the sample buffer
            CFRelease(sampleBuffer);

            // increment counter
            self.videoFrameIdx += 1;

            result(@(true));
        }
        else if ([@"appendAudioFrame" isEqualToString:call.method])
        {
            NSDictionary *args = (NSDictionary*) call.arguments;
            FlutterStandardTypedData *rawPcmData = args[@"rawPcm"];
            NSData *audioSampleData = rawPcmData.data;

            // Check if the asset writer is initialized
            if (!self.mAssetWriter) {
                result([FlutterError errorWithCode:@"AssetWriterUnavailable"
                                        message:@"AVAssetWriter is not initialized"
                                        details:nil]);
                return;
            }

            // Check if the audio input is initialized
            if (!self.mAudioInput) {
                result([FlutterError errorWithCode:@"AVAssetWriterInputUnavailable"
                                        message:@"AVAssetWriterInput is not initialized"
                                        details:nil]);
                return;
            }

            // Check status
            if (self.mAssetWriter.status == AVAssetWriterStatusFailed) {
                NSError *error = self.mAssetWriter.error;
                result([FlutterError errorWithCode:@"AVAssetWriterStatus"
                                        message:@"AVAssetWriter bad status"
                                        details:[error localizedDescription]]);
                return;
            }

            // Ensure that we have started the session
            if (self.mAssetWriter.status != AVAssetWriterStatusWriting) {
                [self.mAssetWriter startWriting];
                [self.mAssetWriter startSessionAtSourceTime:kCMTimeZero];
            }

            // Create audio sample buffer from the provided data
            CMSampleBufferRef sampleBuffer = createAudioSampleBuffer(
                self.fps, self.audioFrameIdx, self.audioChannels, self.sampleRate, audioSampleData);
            if (!sampleBuffer) {
                result([FlutterError errorWithCode:@"SampleBufferCreationFailed"
                                        message:@"Failed to create audio sample buffer"
                                        details:nil]);
                return;
            }

            // wait until ready
            while (self.mAudioInput.readyForMoreMediaData == FALSE) {
                NSDate *maxDate = [NSDate dateWithTimeIntervalSinceNow:0.1];
                [[NSRunLoop currentRunLoop] runUntilDate:maxDate];
            }

            // wait until ready
            while (CMSampleBufferDataIsReady(sampleBuffer) == FALSE) {
                NSDate *maxDate = [NSDate dateWithTimeIntervalSinceNow:0.1];
                [[NSRunLoop currentRunLoop] runUntilDate:maxDate];
            }

            // Append the sample buffer
            if (![self.mAudioInput appendSampleBuffer:sampleBuffer]) {
                NSError *error = self.mAssetWriter.error;
                NSString *errorDetails = error ? [error localizedDescription] : @"Unknown error";
                result([FlutterError errorWithCode:@"SampleBufferAppendFailed"
                                        message:@"Failed to append audio sample buffer"
                                        details:errorDetails]);
                CFRelease(sampleBuffer);
                return;
            }

            // Release the sample buffer
            CFRelease(sampleBuffer);

            // increment counter
            self.audioFrameIdx += 1;

            result(@(true));
        }
        else if ([@"finish" isEqualToString:call.method])
        {
            // Let the clip readers go with the film they were opened for.
            //
            // Each holds a decode session and a retained pixel buffer, and a
            // 4K source is tens of megabytes of them. Keeping the map across
            // exports would leak a session per source per film — and worse,
            // a second export of the same file would resume the first one's
            // reader partway through rather than starting at the beginning.
            [self.mClipReaders removeAllObjects];

            // Check if the asset writer is initialized
            if (!self.mAssetWriter) {
                result([FlutterError errorWithCode:@"AssetWriterUnavailable"
                                        message:@"AVAssetWriter is not initialized"
                                        details:nil]);
                return;
            }

            // Mark audio as finished
            if (self.audioChannels != 0 && self.sampleRate != 0) {
                [self.mAudioInput markAsFinished];
            }

            // Mark video as finished
            if (self.width != 0 && self.height != 0) {
                [self.mVideoInput markAsFinished];
            }
            

            // Setup a dispatch group to wait for the finishWriting completion
            dispatch_group_t dispatchGroup = dispatch_group_create();
            dispatch_group_enter(dispatchGroup);

            [self.mAssetWriter finishWritingWithCompletionHandler:^{
                // This block is executed when writing is finished
                dispatch_group_leave(dispatchGroup);
            }];

            // Wait for the completion handler to finish
            dispatch_group_wait(dispatchGroup, DISPATCH_TIME_FOREVER);

            // After writing is complete, check for any errors
            if (self.mAssetWriter.status == AVAssetWriterStatusFailed) {
                NSError *error = self.mAssetWriter.error;
                NSString *errorDetails = error ? [error localizedDescription] : @"Unknown error";
                result([FlutterError errorWithCode:@"AssetWriterFinishFailed"
                                        message:@"Failed to finish writing"
                                        details:errorDetails]);
                return;
            }

            result(@(true));
        }
        else if ([@"mux" isEqualToString:call.method])
        {
            NSDictionary *args = (NSDictionary *)call.arguments;
            [self muxVideo:args[@"videoPath"]
                 withAudio:args[@"audioPath"]
                        to:args[@"outputPath"]
              audioBitrate:[args[@"audioBitrate"] intValue]
                    result:result];
        }
        else
        {
            result([FlutterError errorWithCode:@"functionNotImplemented" message:call.method details:nil]);
        }
    }
    @catch (NSException *e)
    {
        NSString *stackTrace = [[e callStackSymbols] componentsJoinedByString:@"\n"];
        NSDictionary *details = @{@"stackTrace": stackTrace};
        result([FlutterError errorWithCode:@"iosException" message:[e reason] details:details]);
    }
}

/// Joins a finished video file and a WAV into one `.mp4`.
///
/// The video is **passed through** — its compressed samples are copied across
/// with `outputSettings:nil`, so a two-and-a-half minute 1080p film costs a
/// container rewrite rather than a re-encode. Only the audio is encoded, from
/// linear PCM to AAC.
///
/// Both inputs are driven by `requestMediaDataWhenReadyOnQueue`, which is the
/// point. Feeding two `AVAssetWriterInput`s by hand does not work: the writer
/// only frees one input as the other progresses, and anything pushing samples
/// from outside — a method channel, say — deadlocks the moment one input goes
/// not-ready. Measured from Dart, alternating stalled after 37 video frames
/// and audio-first stalled after 95 audio frames. Letting the writer ask for
/// data when *it* is ready is the only shape that works.
- (void)muxVideo:(NSString *)videoPath
       withAudio:(NSString *)audioPath
              to:(NSString *)outputPath
    audioBitrate:(int)audioBitrate
          result:(FlutterResult)result
{
    NSError *error = nil;

    AVURLAsset *videoAsset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:videoPath] options:nil];
    AVURLAsset *audioAsset = [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:audioPath] options:nil];

    AVAssetTrack *videoTrack = FQVEFirstTrack(videoAsset, AVMediaTypeVideo);
    AVAssetTrack *audioTrack = FQVEFirstTrack(audioAsset, AVMediaTypeAudio);
    if (videoTrack == nil) {
        result([FlutterError errorWithCode:@"MuxNoVideoTrack" message:videoPath details:nil]);
        return;
    }
    if (audioTrack == nil) {
        result([FlutterError errorWithCode:@"MuxNoAudioTrack" message:audioPath details:nil]);
        return;
    }

    [[NSFileManager defaultManager] removeItemAtPath:outputPath error:nil];

    AVAssetReader *videoReader = [AVAssetReader assetReaderWithAsset:videoAsset error:&error];
    if (error) {
        result([FlutterError errorWithCode:@"MuxReaderFailed" message:[error localizedDescription] details:nil]);
        return;
    }
    // nil output settings: hand back the compressed samples untouched.
    AVAssetReaderTrackOutput *videoOutput =
        [AVAssetReaderTrackOutput assetReaderTrackOutputWithTrack:videoTrack outputSettings:nil];
    [videoReader addOutput:videoOutput];

    AVAssetReader *audioReader = [AVAssetReader assetReaderWithAsset:audioAsset error:&error];
    if (error) {
        result([FlutterError errorWithCode:@"MuxReaderFailed" message:[error localizedDescription] details:nil]);
        return;
    }
    AVAssetReaderTrackOutput *audioOutput =
        [AVAssetReaderTrackOutput assetReaderTrackOutputWithTrack:audioTrack
                                                  outputSettings:@{
                                                      AVFormatIDKey : @(kAudioFormatLinearPCM),
                                                      AVLinearPCMIsBigEndianKey : @(NO),
                                                      AVLinearPCMIsFloatKey : @(NO),
                                                      AVLinearPCMBitDepthKey : @(16),
                                                      AVLinearPCMIsNonInterleaved : @(NO),
                                                  }];
    [audioReader addOutput:audioOutput];

    AVAssetWriter *writer = [AVAssetWriter assetWriterWithURL:[NSURL fileURLWithPath:outputPath]
                                                     fileType:AVFileTypeMPEG4
                                                        error:&error];
    if (error) {
        result([FlutterError errorWithCode:@"MuxWriterFailed" message:[error localizedDescription] details:nil]);
        return;
    }

    // The format hint carries the H.264 parameter sets across, which is what
    // makes a passthrough input legal without describing the codec again.
    AVAssetWriterInput *videoInput =
        [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo
                                           outputSettings:nil
                                         sourceFormatHint:(__bridge CMFormatDescriptionRef)
                                                              [[videoTrack formatDescriptions] firstObject]];
    videoInput.expectsMediaDataInRealTime = NO;
    // Preserve any rotation the encoder recorded.
    videoInput.transform = videoTrack.preferredTransform;
    if (![writer canAddInput:videoInput]) {
        result([FlutterError errorWithCode:@"MuxVideoInputRejected" message:@"canAddInput was false" details:nil]);
        return;
    }
    [writer addInput:videoInput];

    const AudioStreamBasicDescription *sourceFormat =
        CMAudioFormatDescriptionGetStreamBasicDescription(
            (__bridge CMAudioFormatDescriptionRef)[[audioTrack formatDescriptions] firstObject]);
    AVAssetWriterInput *audioInput =
        [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeAudio
                                           outputSettings:@{
                                               AVFormatIDKey : @(kAudioFormatMPEG4AAC),
                                               AVSampleRateKey : @(sourceFormat->mSampleRate),
                                               AVNumberOfChannelsKey : @(sourceFormat->mChannelsPerFrame),
                                               AVEncoderBitRateKey : @(audioBitrate),
                                           }];
    audioInput.expectsMediaDataInRealTime = NO;
    if (![writer canAddInput:audioInput]) {
        result([FlutterError errorWithCode:@"MuxAudioInputRejected" message:@"canAddInput was false" details:nil]);
        return;
    }
    [writer addInput:audioInput];

    if (![writer startWriting]) {
        result([FlutterError errorWithCode:@"MuxStartFailed"
                                   message:[[writer error] localizedDescription]
                                   details:nil]);
        return;
    }
    [writer startSessionAtSourceTime:kCMTimeZero];

    // Both return values matter. `copyNextSampleBuffer` does not fail politely
    // on a reader that never started — it throws NSInternalInconsistencyException
    // from inside the writer's own dispatch queue, which is an uncatchable
    // crash rather than an error the Dart side can see.
    if (![videoReader startReading]) {
        result([FlutterError errorWithCode:@"MuxVideoReadFailed"
                                   message:[[videoReader error] localizedDescription]
                                   details:videoPath]);
        return;
    }
    if (![audioReader startReading]) {
        result([FlutterError errorWithCode:@"MuxAudioReadFailed"
                                   message:[[audioReader error] localizedDescription]
                                   details:audioPath]);
        return;
    }

    dispatch_group_t group = dispatch_group_create();

    // Each block captures its *reader* as well as its output, and that is
    // load-bearing rather than tidy. `requestMediaDataWhenReadyOnQueue` copies
    // the block and retains what the block names — so naming only the output
    // let both AVAssetReaders be deallocated the moment this method returned,
    // leaving their outputs parented to nothing. `copyNextSampleBuffer` then
    // throws NSInternalInconsistencyException, from the writer's own dispatch
    // queue, where no @try can catch it: the whole app goes down complaining
    // that the output was never added to a reader — which it was.
    dispatch_group_enter(group);
    dispatch_queue_t videoQueue = dispatch_queue_create("fqve.mux.video", DISPATCH_QUEUE_SERIAL);
    [videoInput requestMediaDataWhenReadyOnQueue:videoQueue usingBlock:^{
        while (videoInput.readyForMoreMediaData) {
            if (videoReader.status != AVAssetReaderStatusReading) {
                [videoInput markAsFinished];
                dispatch_group_leave(group);
                return;
            }
            CMSampleBufferRef buffer = [videoOutput copyNextSampleBuffer];
            if (buffer == NULL) {
                [videoInput markAsFinished];
                dispatch_group_leave(group);
                return;
            }
            BOOL ok = [videoInput appendSampleBuffer:buffer];
            CFRelease(buffer);
            if (!ok) {
                [videoInput markAsFinished];
                dispatch_group_leave(group);
                return;
            }
        }
    }];

    dispatch_group_enter(group);
    dispatch_queue_t audioQueue = dispatch_queue_create("fqve.mux.audio", DISPATCH_QUEUE_SERIAL);
    [audioInput requestMediaDataWhenReadyOnQueue:audioQueue usingBlock:^{
        while (audioInput.readyForMoreMediaData) {
            if (audioReader.status != AVAssetReaderStatusReading) {
                [audioInput markAsFinished];
                dispatch_group_leave(group);
                return;
            }
            CMSampleBufferRef buffer = [audioOutput copyNextSampleBuffer];
            if (buffer == NULL) {
                [audioInput markAsFinished];
                dispatch_group_leave(group);
                return;
            }
            BOOL ok = [audioInput appendSampleBuffer:buffer];
            CFRelease(buffer);
            if (!ok) {
                [audioInput markAsFinished];
                dispatch_group_leave(group);
                return;
            }
        }
    }];

    dispatch_group_notify(group, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [writer finishWritingWithCompletionHandler:^{
            dispatch_async(dispatch_get_main_queue(), ^{
                if (writer.status == AVAssetWriterStatusCompleted) {
                    result(@(true));
                } else {
                    NSError *failure = writer.error;
                    result([FlutterError errorWithCode:@"MuxFinishFailed"
                                               message:failure ? [failure localizedDescription] : @"Unknown error"
                                               details:nil]);
                }
            });
        }];
    });
}

- (NSString*)parseProfileLevel:(NSString*)str {
    if ([str isEqualToString:@"high40"])                 {return AVVideoProfileLevelH264High40;}
    else if ([str isEqualToString:@"high41"])            {return AVVideoProfileLevelH264High41;}
    else if ([str isEqualToString:@"main30"])            {return AVVideoProfileLevelH264Main30;}
    else if ([str isEqualToString:@"main31"])            {return AVVideoProfileLevelH264Main31;}
    else if ([str isEqualToString:@"main32"])            {return AVVideoProfileLevelH264Main32;}
    else if ([str isEqualToString:@"main41"])            {return AVVideoProfileLevelH264Main41;}
    else if ([str isEqualToString:@"baseline30"])        {return AVVideoProfileLevelH264Baseline30;}
    else if ([str isEqualToString:@"baseline31"])        {return AVVideoProfileLevelH264Baseline31;}
    else if ([str isEqualToString:@"baseline41"])        {return AVVideoProfileLevelH264Baseline41;}
    else if ([str isEqualToString:@"highAutoLevel"])     {return AVVideoProfileLevelH264HighAutoLevel;}
    else if ([str isEqualToString:@"mainAutoLevel"])     {return AVVideoProfileLevelH264MainAutoLevel;}
    else if ([str isEqualToString:@"baselineAutoLevel"]) {return AVVideoProfileLevelH264BaselineAutoLevel;}
    else                                                 {return AVVideoProfileLevelH264BaselineAutoLevel;}
}
@end


/// Composites one clip frame under the overlay, inside one rectangle.
///
/// **Source-over, and the overlay is straight alpha.** The painter cleared the
/// rectangle, so the overlay is transparent there and `out = over + clip*(1-a)`
/// reduces to the clip in the middle while blending correctly along the
/// antialiased edge. That is the whole reason the rect is cleared rather than
/// skipped: a skipped rect would still hold the basemap, and this arithmetic
/// would then blend the clip with a map nobody wants to see.
///
/// Nearest-neighbour sampling. The destination is smaller than a 4K source by a
/// wide margin, so this is a downscale, and a bilinear tap would cost four
/// reads per pixel to soften an image the encoder is about to requantise
/// anyway. Worth revisiting only if a clip is ever scaled up.
static void blendClipIntoFrame(uint8_t *dst,
                               int frameWidth,
                               int frameHeight,
                               CVPixelBufferRef clip,
                               const FQVEClipColor *color,
                               int rx, int ry, int rw, int rh,
                               int quarterTurns)
{
    if (!clip || rw <= 0 || rh <= 0) { return; }

    CVPixelBufferLockBaseAddress(clip, kCVPixelBufferLock_ReadOnly);
    const uint16_t *lumaPlane =
        (const uint16_t *)CVPixelBufferGetBaseAddressOfPlane(clip, 0);
    const uint16_t *chromaPlane =
        (const uint16_t *)CVPixelBufferGetBaseAddressOfPlane(clip, 1);
    size_t lumaStride = CVPixelBufferGetBytesPerRowOfPlane(clip, 0);
    size_t chromaStride = CVPixelBufferGetBytesPerRowOfPlane(clip, 1);
    int srcW = (int)CVPixelBufferGetWidth(clip);
    int srcH = (int)CVPixelBufferGetHeight(clip);
    if (!lumaPlane || !chromaPlane || !color || srcW <= 0 || srcH <= 0) {
        CVPixelBufferUnlockBaseAddress(clip, kCVPixelBufferLock_ReadOnly);
        return;
    }

    // A quarter turn swaps which source axis each destination axis walks. The
    // rect is already the turned shape, because `displayAspect` applied the
    // rotation, so only the sampling has to know about it.
    int turns = ((quarterTurns % 4) + 4) % 4;

    for (int y = 0; y < rh; y++) {
        int dy = ry + y;
        if (dy < 0 || dy >= frameHeight) { continue; }
        for (int x = 0; x < rw; x++) {
            int dx = rx + x;
            if (dx < 0 || dx >= frameWidth) { continue; }

            float u = (x + 0.5f) / rw;
            float v = (y + 0.5f) / rh;
            float su, sv;
            switch (turns) {
                case 1:  su = v;        sv = 1.0f - u;  break;
                case 2:  su = 1.0f - u; sv = 1.0f - v;  break;
                case 3:  su = 1.0f - v; sv = u;         break;
                default: su = u;        sv = v;         break;
            }
            int sx = (int)(su * srcW);
            int sy = (int)(sv * srcH);
            if (sx < 0) { sx = 0; } else if (sx >= srcW) { sx = srcW - 1; }
            if (sy < 0) { sy = 0; } else if (sy >= srcH) { sy = srcH - 1; }

            // **Ten bits live in the high bits of each 16-bit word**, so the
            // shift is six rather than nothing. Measured off a real decode
            // rather than read from a header: the luma of a bright row came back
            // between 17216 and 56256, which is 269 to 879 once shifted — a
            // limited-range span. Reading it unshifted would have scaled the
            // whole clip by sixty-four and looked like a blown exposure.
            const uint16_t *lumaRow =
                (const uint16_t *)((const uint8_t *)lumaPlane + (size_t)sy * lumaStride);
            const uint16_t *chromaRow =
                (const uint16_t *)((const uint8_t *)chromaPlane
                                   + (size_t)(sy / 2) * chromaStride);
            const int yy = lumaRow[sx] >> 6;
            const int cb = chromaRow[(sx / 2) * 2] >> 6;
            const int cr = chromaRow[(sx / 2) * 2 + 1] >> 6;
            const uint32_t rgb = FQVEClipColorToRgb(color, yy, cb, cr);

            uint8_t *d = dst + ((size_t)dy * frameWidth + dx) * 4;

            // Destination is RGBA **straight**; the clip arrives BGRA.
            //
            // The overlay is weighted by its own alpha, and that word is the
            // whole bug this line used to carry. `d + s * inv` is the *premul*
            // form of source-over — correct when the destination's colour has
            // already been multiplied by its coverage. `renderSingleFrame`
            // reads back `rawStraightRgba` on purpose, so a half-cleared pixel
            // still holds its colour at full strength and half the clip was
            // being *added* to a whole map. Past 255 the uint8_t wrapped, each
            // channel independently, which is why it did not read as
            // washed-out but as magenta and cyan confetti.
            //
            // It was invisible for as long as a hole was cleared outright:
            // alpha was only ever 0 or 255, and at 0 the painter had zeroed the
            // colour too, so the two formulas agree. Introducing a *partial*
            // clear to crossfade a clip is what made a fractional alpha
            // possible, and the corruption appeared at exactly the fades and
            // nowhere else.
            //
            // `a + inv == 255`, so this cannot overflow by construction.
            int a = d[3];
            if (a == 255) { continue; }
            int inv = 255 - a;
            d[0] = (uint8_t)((d[0] * a + (int)((rgb >> 16) & 0xFF) * inv) / 255);
            d[1] = (uint8_t)((d[1] * a + (int)((rgb >> 8) & 0xFF) * inv) / 255);
            d[2] = (uint8_t)((d[2] * a + (int)(rgb & 0xFF) * inv) / 255);
            d[3] = 255;
        }
    }

    CVPixelBufferUnlockBaseAddress(clip, kCVPixelBufferLock_ReadOnly);
}


CMSampleBufferRef createVideoSampleBuffer(int fps, int frameIdx, int width, int height, NSData *videoFrameData)
{
#if TARGET_OS_IOS
    NSDictionary *attributes = @{(id)kCVPixelBufferIOSurfacePropertiesKey: @{}};
#else
    NSDictionary *attributes = NULL;
#endif

    CVPixelBufferRef pixelBuffer = NULL;
    CVReturn cvReturn = CVPixelBufferCreate(
                            kCFAllocatorDefault,
                            width,
                            height,
                            kCVPixelFormatType_32BGRA,
                            (__bridge CFDictionaryRef) attributes,
                            &pixelBuffer);
    
    if (cvReturn != kCVReturnSuccess) {
        NSLog(@"Failed to create pixel buffer: %d", cvReturn);
        return NULL;
    }
    
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    
    void *bgraData = CVPixelBufferGetBaseAddress(pixelBuffer);
    uint8_t *rgbaData = (uint8_t *)[videoFrameData bytes];

    // convert RGBA to BGRA and copy to pixel buffer
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    for (int y = 0; y < height; y++) {
        uint8_t *rgbaRow = rgbaData + y * width * 4;
        uint8_t *bgraRow = bgraData + y * bytesPerRow;
        for (int x = 0; x < width; x++) {
            size_t pixelIndex = x * 4;
            bgraRow[pixelIndex]     = rgbaRow[pixelIndex + 2]; // Blue
            bgraRow[pixelIndex + 1] = rgbaRow[pixelIndex + 1]; // Green
            bgraRow[pixelIndex + 2] = rgbaRow[pixelIndex];     // Red
            bgraRow[pixelIndex + 3] = rgbaRow[pixelIndex + 3]; // Alpha
        }
    }
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);

    CMVideoFormatDescriptionRef formatDescription;
    OSStatus status = CMVideoFormatDescriptionCreateForImageBuffer(
                            kCFAllocatorDefault,
                            pixelBuffer,
                            &formatDescription);
    
    if (status != noErr) {
        NSLog(@"Failed to create format description: %d", status);
        CVPixelBufferRelease(pixelBuffer);
        return NULL;
    }

    CMSampleTimingInfo timingInfo = {0};
    timingInfo.duration = CMTimeMake(1, fps);
    timingInfo.decodeTimeStamp = kCMTimeInvalid;
    timingInfo.presentationTimeStamp = CMTimeMake(frameIdx, fps);
    
    CMSampleBufferRef sampleBuffer = NULL;

    status = CMSampleBufferCreateForImageBuffer(
                          kCFAllocatorDefault, // allocator
                          pixelBuffer, // cvImage
                          true, // dataReady
                          NULL, // makeDataReadyCallback
                          NULL, // makeDataReadyRefContext
                          formatDescription, // formatDescription
                          &timingInfo, // sampleTiming
                          &sampleBuffer ); // out
    
    if (status != noErr) {
        NSLog(@"Failed to create sample buffer: %d", status);
        CVPixelBufferRelease(pixelBuffer);
        CFRelease(formatDescription);
        return NULL;
    }

    CVPixelBufferRelease(pixelBuffer);
    
    return sampleBuffer;
}


CMSampleBufferRef createAudioSampleBuffer(int fps, int frameIdx, int audioChannels, int sampleRate, NSData *audioSampleData)
{
    int numSamples = (int)[audioSampleData length] / sizeof(int16_t);

    // Own a copy of the samples rather than wrapping the caller's.
    //
    // This passed the NSData's bytes with kCFAllocatorNull as the block
    // allocator, which tells CoreMedia to neither copy them nor take
    // responsibility for freeing them. Those bytes belong to the method
    // channel's argument and are gone once the call returns, while
    // AVAssetWriterInput retains the sample buffer and reads it whenever it
    // gets to it. Every audio frame was a use-after-free — latent until now,
    // because nothing had ever appended one.
    CMBlockBufferRef blockBuffer = NULL;
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(
                         kCFAllocatorDefault,
                         NULL, // let CoreMedia allocate, so the block owns it
                         [audioSampleData length],
                         kCFAllocatorDefault,
                         NULL,
                         0,
                         [audioSampleData length],
                         kCMBlockBufferAssureMemoryNowFlag,
                         &blockBuffer);

    if (status != kCMBlockBufferNoErr) {
        NSLog(@"Failed to create block buffer: %d", status);
        return NULL;
    }

    status = CMBlockBufferReplaceDataBytes([audioSampleData bytes],
                                           blockBuffer,
                                           0,
                                           [audioSampleData length]);
    if (status != kCMBlockBufferNoErr) {
        NSLog(@"Failed to copy audio into block buffer: %d", status);
        CFRelease(blockBuffer);
        return NULL;
    }

    AudioStreamBasicDescription audioFormatDescription = {
        .mSampleRate = sampleRate,
        .mFormatID = kAudioFormatLinearPCM,
        .mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
        .mBytesPerPacket = 2 * audioChannels,
        .mFramesPerPacket = 1,
        .mBytesPerFrame = 2 * audioChannels,
        .mChannelsPerFrame = audioChannels,
        .mBitsPerChannel = 16,
        .mReserved = 0
    };

    CMAudioFormatDescriptionRef formatDescription = NULL;

    status = CMAudioFormatDescriptionCreate(
                kCFAllocatorDefault,
                &audioFormatDescription,
                0, // layout num
                NULL, // speaker location layout
                0, // format's magic cookie size
                NULL, // format's magic cookie
                NULL, // extensions
                &formatDescription);

    if (status != noErr) {
        NSLog(@"Failed to create audio format description: %d", status);
        CFRelease(blockBuffer);
        return NULL;
    }

    CMSampleTimingInfo timingInfo = {
        .duration = CMTimeMake(1, fps),
        .decodeTimeStamp = kCMTimeInvalid,
        .presentationTimeStamp = CMTimeMake(frameIdx, fps),
    };

    CMSampleBufferRef sampleBuffer = NULL;

    status = CMSampleBufferCreate(kCFAllocatorDefault,// allocator
                         blockBuffer, // dataBuffer
                         TRUE, // dataReady
                         NULL, // dataReadyCallback
                         NULL, // makeDataReadyRefContext
                         formatDescription,
                         numSamples,
                         1, // numSampleTimingEntries
                         &timingInfo, // timing info
                         0, // number of samples (frames)
                         NULL, // sizes of each sample (frame)
                         &sampleBuffer);

    if (status != noErr) {
        NSLog(@"Failed to create sample buffer: %d", status);
        CFRelease(blockBuffer);
        CFRelease(formatDescription);
        return NULL;
    }

    CFRelease(blockBuffer);
    return sampleBuffer;
}

