
// SPM has no prefix header, so TARGET_OS_OSX must be imported rather than inherited.
#import <TargetConditionals.h>

#if TARGET_OS_OSX
#import <FlutterMacOS/FlutterMacOS.h>
#else
#import <Flutter/Flutter.h>
#endif

#define NAMESPACE @"flutter_quick_video_encoder"

@interface FlutterQuickVideoEncoderPlugin : NSObject<FlutterPlugin>
@end
