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

  static const EventChannel _thermalChannel = const EventChannel(
    'flutter_quick_video_encoder/thermal',
  );

  /// Every change in how hard the device is limiting itself.
  ///
  /// Pushed by the platform rather than polled, and the first event arrives on
  /// subscription so a listener that joins mid-render learns where it already
  /// is — on a device that has reached its ceiling, the next transition may
  /// never come.
  ///
  /// Each event carries:
  ///
  ///  - `level`      0..3, and **the only field two platforms may be compared
  ///                 on**. Apple reports four states and Android seven, so the
  ///                 mapping happens where that knowledge lives rather than in
  ///                 the caller: nominal/fair/serious/critical against
  ///                 NONE/LIGHT/MODERATE/SEVERE-and-above.
  ///  - `name`       the platform's own word, for a log a human reads.
  ///  - `throttling` whether it is worth telling a rider about, which is level
  ///                 two and up. Below that is normal under any sustained load
  ///                 and saying so would only teach people to ignore it.
  ///  - `headroom`   how close to the threshold, 1.0 being at it. **Android
  ///                 only, and absent rather than invented elsewhere** —
  ///                 Apple has no forecast, and a gauge showing a made-up
  ///                 number cannot be unseen.
  ///
  /// Errors with `ThermalUnavailable` where the platform cannot answer, rather
  /// than going quiet — silence reads identically to "never throttled", which
  /// is the one thing it must not be mistaken for.
  static Stream<Map<String, Object?>> get thermalChanges =>
      _thermalChannel.receiveBroadcastStream().map(
            (event) => Map<String, Object?>.from(event as Map),
          );

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

  /// Which of [paths] this device cannot decode, and why.
  ///
  /// Keyed by path, empty when every clip opened and produced a frame. Named
  /// paths rather than a count, because the message a rider needs is which of
  /// *their* files this device cannot read.
  ///
  /// **Ask before the render, not during it.** A clip that cannot be opened is
  /// not an error at export time: the compositor's documented behavior is to
  /// leave that rectangle as the painter cleared it, which encodes as a black
  /// window in a film that is otherwise valid, with nothing logged. So the
  /// alternative to calling this is not a late failure, it is a published
  /// video with a hole in it.
  ///
  /// It opens each clip exactly the way the render does and decodes **one**
  /// frame, so it costs a decoder start per clip — cheap next to an export, and
  /// worth it in the second before the button rather than four minutes after
  /// it.
  ///
  /// **One frame is the limit of what it promises, and that is a real limit.**
  /// Measured on macOS: zeroing 200 KB out of the middle of a 431 KB mp4 —
  /// close to half the file — still answers decodable, because the first frame
  /// is intact and nothing reads further. So this catches a file that is not a
  /// video, that has no video track, or whose codec this device cannot decode
  /// at all; it does not catch damage past the opening. A full integrity pass
  /// would cost a complete decode of every clip, which is the export itself.
  ///
  /// Both platforms answer. It neither subsumes nor is subsumed by an existence
  /// or byte-length check: a truncated file fails here *and* fails those, while
  /// a file damaged in the middle passes here and passes those. They are
  /// different questions and both are worth asking.
  static Future<Map<String, String>> checkClipsDecodable(
    List<String> paths,
  ) async {
    if (paths.isEmpty) {
      return const {};
    }
    final result = await _invokeMethod<Map<Object?, Object?>>(
      'checkClipsDecodable',
      {'paths': paths},
    );
    return {
      for (final entry in (result ?? const {}).entries)
        entry.key.toString(): entry.value.toString(),
    };
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
