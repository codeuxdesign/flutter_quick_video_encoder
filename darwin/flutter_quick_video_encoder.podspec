#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_quick_video_encoder.podspec` to validate before publishing.
#
# Kept alongside Package.swift so the plugin still builds for anyone whose Flutter
# predates Swift Package Manager support, or who has turned it off. Both manifests
# compile the same sources; only the way they are declared differs.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_quick_video_encoder'
  s.version          = '0.0.1'
  s.summary          = 'Flutter plugin for video encoding'
  s.description      = 'Flutter plugin for video encoding'
  s.homepage         = 'https://github.com/chipweinberger/flutter_quick_video_encoder'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Chip Weinberger' => 'weinbergerc@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files        = 'flutter_quick_video_encoder/Sources/flutter_quick_video_encoder/**/*.{h,m}'
  s.public_header_files = 'flutter_quick_video_encoder/Sources/flutter_quick_video_encoder/include/**/*.h'

  s.ios.dependency 'Flutter'
  s.osx.dependency 'FlutterMacOS'

  # Matches the floors Package.swift declares, which are Flutter's own minimums.
  s.ios.deployment_target = '13.0'
  s.osx.deployment_target = '10.15'

  s.framework = 'AVFoundation', 'CoreMedia'
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', }
end
