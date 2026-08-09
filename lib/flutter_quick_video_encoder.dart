import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

enum LogLevel {
  none,
  error,
  standard,
  verbose,
}

// H264 profile level
enum ProfileLevel {
  any,
  high40,
  high41,
  main30,
  main31,
  main32,
  main41,
  baseline30,
  baseline31,
  baseline41,
  highAutoLevel,
  mainAutoLevel,
  baselineAutoLevel,
}

class FlutterQuickVideoEncoder {
  static const MethodChannel _channel = const MethodChannel('flutter_quick_video_encoder/methods');

  // setup values
  static int width = 0;
  static int height = 0;
  static int fps = 0;
  static int audioChannels = 0;
  static int sampleRate = 0;
  static String filepath = '';

  // log level
  static LogLevel logLevel = LogLevel.standard;

  /// set log level
  static Future<void> setLogLevel(LogLevel level) async {
    logLevel = level;
    return await _invokeMethod('setLogLevel', {'log_level': level.index});
  }

  /// setup encoder
  static Future<void> setup(
      {required int width,
      required int height,
      required int fps,
      required int videoBitrate,
      required ProfileLevel profileLevel,
      required int audioChannels,
      required int audioBitrate,
      required int sampleRate,
      required String filepath}) async {
    // H.264 encodes in 16x16 macroblocks and neither AVAssetWriter nor
    // MediaCodec accepts odd dimensions. Android does not reject them, it
    // reads past the end of the plane while converting to YUV and produces a
    // corrupt file or a native crash, several hundred frames in, with nothing
    // pointing back at the size. Upstream issue #8, open since 2024-07-22.
    // Failing here costs one line and names the actual problem.
    if (width % 2 != 0 || height % 2 != 0) {
      throw ArgumentError(
          'Video dimensions must both be even, got ${width}x$height. '
          'H.264 encodes in 16x16 macroblocks; odd sizes corrupt the output '
          'on Android rather than failing cleanly.');
    }
    _createIntermediateDirectories(filepath);
    FlutterQuickVideoEncoder.width = width;
    FlutterQuickVideoEncoder.height = height;
    FlutterQuickVideoEncoder.fps = fps;
    FlutterQuickVideoEncoder.audioChannels = audioChannels;
    FlutterQuickVideoEncoder.sampleRate = sampleRate;
    FlutterQuickVideoEncoder.filepath = filepath;
    return await _invokeMethod('setup', {
      'width': width,
      'height': height,
      'fps': fps,
      'videoBitrate': videoBitrate,
      'profileLevel': profileLevel.toString().split('.')[1],
      'audioChannels': audioChannels,
      'audioBitrate': audioBitrate,
      'sampleRate': sampleRate,
      'filepath': filepath,
    });
  }

  /// append raw rgba video frame, 8 bits per channel
  /// append one frame of straight-alpha RGBA
  ///
  /// [holes] are rectangles the caller wants filled with video rather than with
  /// its own pixels. Each is a map of:
  ///
  ///  - `path`          absolute path to the source file
  ///  - `sourceTimeUs`  how far into that file this frame is
  ///  - `x`,`y`,`w`,`h` destination rectangle, in pixels of this frame
  ///  - `quarterTurns`  how far to turn the source clockwise, 0-3
  ///
  /// The decoded clip is composited *under* [rawRgba] inside each rectangle, so
  /// the caller is expected to have cleared it — straight alpha means ordinary
  /// source-over, and a cleared rect is what lets the clip show through.
  static Future<void> appendVideoFrame(
    Uint8List rawRgba, {
    List<Map<String, Object>> holes = const [],
  }) async {
    assert(rawRgba.length == width * height * 4, "invalid data length");
    return await _invokeMethod('appendVideoFrame', {
      'rawRgba': rawRgba,
      'holes': holes,
    });
  }

  /// append raw pcm audio samples
  ///  - 16 bit, little-endiant
  ///  - when using stereo audio, samples should be interleaved left channel first
  static Future<void> appendAudioFrame(Uint8List rawPcm) async {
    assert(rawPcm.length == (sampleRate * audioChannels * 2) / fps, "invalid data length");
    return await _invokeMethod('appendAudioFrame', {
      'rawPcm': rawPcm,
    });
  }

  /// join a finished video file and a wav into one mp4 at [outputPath]
  ///
  /// The video is passed through — its compressed samples are copied, not
  /// re-encoded — so this costs a container rewrite rather than another
  /// export. Only the audio is encoded, from linear PCM to AAC.
  ///
  /// This exists because audio *cannot* be written alongside video through
  /// [appendAudioFrame]. Two AVAssetWriterInputs only free each other as both
  /// progress, and anything pushing samples from outside deadlocks the moment
  /// one goes not-ready: measured, alternating stalls after 37 video frames
  /// and audio-first stalls after 95 audio frames. Inside the plugin the
  /// writer drives both inputs itself and the problem does not arise.
  static Future<void> mux(
      {required String videoPath,
      required String audioPath,
      required String outputPath,
      int audioBitrate = 192000}) async {
    _createIntermediateDirectories(outputPath);
    return await _invokeMethod('mux', {
      'videoPath': videoPath,
      'audioPath': audioPath,
      'outputPath': outputPath,
      'audioBitrate': audioBitrate,
    });
  }

  /// finish writing the video file
  static Future<void> finish() async {
    try {
      await _invokeMethod('finish');
    } finally {
      width = 0;
      height = 0;
      fps = 0;
      audioChannels = 0;
      sampleRate = 0;
    }
  }

  // create output directory
  static void _createIntermediateDirectories(String filepath) {
    File file = File(filepath);
    Directory dir = file.parent;
    if (!dir.existsSync()) {
      dir.createSync(recursive: true);
    }
  }

  static Future<T?> _invokeMethod<T>(String method, [dynamic arguments]) async {
    // log args
    if (logLevel.index >= LogLevel.standard.index) {
      if (method == "appendVideoFrame") {
        print("[FQVE] '<$method>' rawRgba: ${arguments['rawRgba'].length} bytes");
      } else if (method == "appendAudioFrame") {
        print("[FQVE] '<$method>' rawPcm: ${arguments['rawPcm'].length} bytes");
      } else {
        print("[FQVE] '<$method>' args: $arguments");
      }
    }

    // invoke
    var result = await _channel.invokeMethod(method, arguments);

    // log result
    if (logLevel.index >= LogLevel.standard.index) {
      print("[FQVE] <$method> result: $result");
    }

    return result;
  }
}
