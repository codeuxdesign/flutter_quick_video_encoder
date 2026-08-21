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

/// One decoded clip frame, downsampled far enough to hand to a widget.
///
/// [rgba] is straight RGBA, row major, no padding, alpha 255 throughout —
/// `width * height * 4` bytes, ready for `decodeImageFromPixels` with
/// `PixelFormat.rgba8888`.
///
/// **[width] and [height] are the frame's own, not the caller's request.** A
/// decoded frame carries a crop rectangle, so the area a decoder hands back is
/// not always the size the container advertises, and the downsample rounds. A
/// caller that assumed `maxEdge` square, or assumed the track's natural size,
/// would lay the bytes out sheared.
///
/// **They also describe the frame as *stored*, so a caller laying out a rotated
/// clip has to swap them.** Portrait footage from a camera that recorded
/// landscape and tagged a quarter turn arrives here 1920x1080, and the picture
/// the rider is going to see is 1080x1920. The axis swap is the part that gets
/// missed — "rotation is not applied" reads as a statement about the pixels,
/// and it is equally a statement about these two integers.
///
/// **The pixels are in the file's stored orientation, and the angle is not
/// reported here on purpose.** Rotation is metadata the container carries
/// beside the samples; the export reads it and applies it as a hole's
/// `quarterTurns`, and this deliberately does not surface a second copy. The
/// caller supplies the turn from wherever it already got the one it will pass
/// to [FlutterQuickVideoEncoder.appendVideoFrame] — which means there is
/// exactly one rotation decision in the system and the preview sits downstream
/// of it rather than beside it. Two places that decide which way up a clip is
/// would agree until one of them changed, and then a rider would be trimming
/// against a picture the film does not draw, with nothing failing.
///
/// The cost lands on every caller — a `RotatedBox` and an aspect ratio built
/// from the swapped edges — and it is worth paying because *this* mistake is
/// loud. A preview turned the wrong way is a sideways picture on screen; a
/// preview that quietly disagreed with the export about rotation is a wrong cut
/// in a published film.
class ClipPreviewFrame {
  const ClipPreviewFrame({
    required this.rgba,
    required this.width,
    required this.height,
  });

  final Uint8List rgba;
  final int width;
  final int height;

  @override
  String toString() => 'ClipPreviewFrame(${width}x$height)';
}

/// What asking for a clip's frame answered.
///
/// **Three outcomes, because two of them used to be one value and they mean
/// opposite things.** A nullable frame collapsed *this clip has no picture to
/// give* and *your call was dropped because you let go* into a single `null`,
/// which was invisible while the only caller was a handle preview: it redraws
/// either way, so it could not tell and did not care.
///
/// A caller that *keeps* the answer can tell, and the difference is the whole
/// meaning of what it keeps. The clip stills cache logged every retired decode
/// as a damaged file, so one rider stepping to the next wizard step while eight
/// cold clips were still decoding wrote eight lines about eight healthy files —
/// two states behind one message, which is what docs/SILENT-FAILURES.md is a
/// list of.
///
/// Sealed, so the compiler names every place that has to decide rather than
/// letting a caller keep treating the absent case as one thing.
sealed class ClipFrameOutcome {
  const ClipFrameOutcome();
}

/// The frame, decoded.
class ClipFrameReady extends ClipFrameOutcome {
  const ClipFrameReady(this.frame);

  final ClipPreviewFrame frame;

  @override
  String toString() => 'ClipFrameReady($frame)';
}

/// The clip has no picture to give at that instant.
///
/// A container that will not open, a file that has moved, a device with no
/// decoder for it. **A fact about the clip**, so it is worth reporting and
/// worth remembering.
class ClipFrameNone extends ClipFrameOutcome {
  const ClipFrameNone();

  @override
  String toString() => 'ClipFrameNone()';
}

/// The call was dropped because the screen that asked let go.
///
/// **A fact about the caller, not about the clip.** The decode was correct and
/// unfinished; asking again answers normally. Nothing about this should be
/// stored, and nothing about it should be reported as a damaged file.
///
/// Produced whenever [releaseClipPreviews] runs while decodes are queued, which
/// is routine: a rider stepping off the clips screen retires the backlog.
class ClipFrameRetired extends ClipFrameOutcome {
  const ClipFrameRetired();

  @override
  String toString() => 'ClipFrameRetired()';
}

/// One photograph, decoded to bounded RGBA by the platform.
///
/// **Carries what it is, not just what it holds.** [format] and [colorSpace]
/// are `rgba8888` and `srgb` today and nothing else is produced — but naming
/// them is what keeps a wider pixel from being a breaking change later. A bare
/// `Uint8List` would have every call site silently assuming eight bits, and the
/// day that stops being true is the day none of them say so.
class StillFrame {
  const StillFrame({
    required this.rgba,
    required this.width,
    required this.height,
    required this.format,
    required this.colorSpace,
    required this.hasGainmap,
  });

  final Uint8List rgba;
  final int width;
  final int height;

  /// Always `rgba8888` today. See the class comment.
  final String format;

  /// Always `srgb` today.
  final String colorSpace;

  /// Whether the source carried an HDR gain map.
  ///
  /// Reported and unused: the still path is SDR throughout. It is here because
  /// "does a bounded decode keep the gain map" is a question the HDR work has
  /// to answer on a device, and asking it costs nothing now.
  final bool hasGainmap;

  @override
  String toString() => 'StillFrame(${width}x$height $format/$colorSpace'
      '${hasGainmap ? ' +gainmap' : ''})';
}

class FlutterQuickVideoEncoder {
  static const MethodChannel _channel =
      const MethodChannel('flutter_quick_video_encoder/methods');

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

  /// How long the clip at [path] runs, according to the reader that will
  /// decode it. Null when this device cannot tell.
  ///
  /// **Ask the container first; this is the fallback.** An MP4 states its own
  /// length in `moov/mvhd`, and reading it there costs a few hundred bytes and
  /// no platform call. Two shapes defeat that and neither is damage: a
  /// *fragmented* MP4 is specified to write `mvhd.duration` as zero — its
  /// samples are not in the `moov` to be totalled — and may omit the
  /// `mvex/mehd` that carries the intended length, after which nothing short of
  /// walking every fragment in the file knows the answer. That walk is what the
  /// platform has already implemented.
  ///
  /// **The authority argument matters more than the availability one.** This
  /// answers from `MediaExtractor` on Android and the video track's own time
  /// range on Apple — which is to say, from the object the export will later
  /// seek inside. A trim handle bounded by any *other* number can be dragged to
  /// a moment the export cannot reach, and the film then comes back shorter
  /// than the rider asked for with nothing reporting why.
  ///
  /// Video tracks only. An audio track routinely runs a frame or two past the
  /// picture, and a film is only as long as its pictures.
  ///
  /// Both platforms answer, off the platform thread. A file this cannot time is
  /// in practice a file this device cannot decode — which
  /// [checkClipsDecodable] already reports by name, so nothing is invented
  /// here to paper over it.
  static Future<double?> clipDuration(String path) async {
    final seconds = await _invokeMethod<double>('clipDuration', {'path': path});
    if (seconds == null || !seconds.isFinite || seconds <= 0) {
      return null;
    }
    return seconds;
  }

  /// The frame [path] shows at [at], as pixels a widget can draw.
  ///
  /// Null when the clip cannot be opened or decoded — the same conditions
  /// [checkClipsDecodable] reports, and for the same reasons.
  ///
  /// **This is the export's answer, not a second opinion.** It runs the same
  /// reader, the same seek and the same YCbCr conversion `appendVideoFrame`
  /// runs, so the frame a rider trims against is the frame the film will show.
  /// A general-purpose player would be easier and is the wrong tool: it decodes
  /// with its own color pipeline, and a preview that disagrees with the export
  /// by even a frame is worse than no preview at all, because the rider is
  /// making a decision against it.
  ///
  /// **The frame is tone-mapped to SDR**, because that is what the export
  /// writes. An HLG or PQ source is run through Report ITU-R BT.2446-1 Method A
  /// on the way to sRGB, exactly as the composite does. If HDR output ever
  /// ships, this has to follow the chosen output space or it stops being a
  /// promise about the film.
  ///
  /// **The frame comes back unrotated, and [ClipPreviewFrame.width] and
  /// [ClipPreviewFrame.height] are the stored edges — swap them to lay out a
  /// clip the container tagged with a quarter or three-quarter turn.** The
  /// angle is not returned; take it from wherever the `quarterTurns` you will
  /// pass to [appendVideoFrame] comes from, so that one rotation decision
  /// serves both the preview and the film. [ClipPreviewFrame] has the argument
  /// for why that asymmetry is deliberate.
  ///
  /// [maxEdge] caps the longer side, and the frame is downsampled
  /// nearest-neighbor to fit — the same sampling the composite uses. It is not
  /// a formality: a 4K frame is 33 MB of RGBA, which is a jetsam risk to hand
  /// across the method channel per scrub position and pointless for a preview a
  /// few hundred pixels wide. A frame already smaller than [maxEdge] is passed
  /// through at its own size rather than scaled up.
  ///
  /// **Calls queue; none of them is dropped or superseded.** Decoding happens
  /// off the platform thread, one request at a time, in the order they arrive —
  /// so a handle that emits ten positions decodes ten frames and the tenth
  /// arrives after the other nine, rather than the first nine being discarded
  /// in its favor. A caller that scrubs therefore has to coalesce: hold at most
  /// one pending position and replace it, rather than issuing one call per
  /// pointer event. Coalescing here instead would mean completing a superseded
  /// call with *something*, and the only spare value is null — which already
  /// means "this clip has no picture" and must not come to mean "a newer
  /// request overtook you" as well.
  ///
  /// **That coalescing is the whole pacing policy. Do not put a timer in front
  /// of it.** Three rules and no constant: with nothing in flight, call
  /// immediately; with something in flight, keep only the newest waiting
  /// instant; when a call returns, start the waiting one. The cadence then
  /// settles at whatever this device decodes at, which is the fastest correct
  /// answer and needs no tuning per codec or per phone — and the gesture always
  /// ends on the instant the finger stopped at, because the last move either
  /// started a decode or is the one still waiting.
  ///
  /// **A trailing debounce looks right and cannot work here.** A dragging finger
  /// emits a move every frame, so the timer resets before it ever expires and
  /// the preview holds still for the whole gesture. Measured on an iPhone 17 Pro
  /// Max against a 4K clip: a 21-point drag at ~16 ms spacing produced *two*
  /// calls, one at the start and one after the finger lifted, and what sat on
  /// screen in between was a sharp, real frame from a position the handle had
  /// left 300 ms and eighty seconds of footage earlier. Nothing looks broken,
  /// which is the whole problem. Debouncing is right for a *discrete* jump — a
  /// tap on a timeline, a keyboard step — and wrong for a continuous drag, and
  /// this API cannot tell which one the caller has.
  ///
  /// **[maxEdge] is in device pixels.** Passing logical pixels yields a picture
  /// at a fraction of the resolution it is drawn at, which reads as soft
  /// *footage* rather than as a soft preview and so tends to be blamed on the
  /// clip. Multiply by the view's `devicePixelRatio`.
  ///
  /// **Readers are cached by path and have to be let go explicitly**, see
  /// [releaseClipPreviews]. What the cache buys is narrower than it sounds, and
  /// the numbers are worth knowing before pacing anything around them. Measured
  /// on macOS, profile, against a 4K HEVC Main10 clip at `maxEdge` 512:
  ///
  ///  - cold open and seek — **96–107 ms**
  ///  - seek on an already-open reader — **74–83 ms**
  ///  - forward inside the two-second window, no seek — **6 ms**
  ///  - the same instant again, nothing decoded — **5–10 ms**
  ///
  /// So opening the file is worth only about twenty of those milliseconds; the
  /// rest is decoding forward from the sync sample before the instant, which no
  /// cache avoids. **What the cache actually buys is the 6 ms case**, and on a
  /// phone that is a smaller target than it looks: a four-minute clip laid out
  /// across a few hundred points of slider is a third of a second of footage per
  /// point, so an ordinary drag leaves the cheap window within a handful of
  /// points. It is the desk where the fast path pays.
  ///
  /// The cache is bounded and separate from the export's, and [setup] drops it,
  /// so a preview can never be holding the decoder an export wants.
  /// Decodes a photograph so that neither edge exceeds [maxEdge].
  ///
  /// **Android only, and it is not a convenience.** Flutter cannot decode HEIF
  /// itself, so on Android a `.heic` falls through to the platform generator —
  /// which decodes at full resolution regardless of any `cacheWidth`, and whose
  /// failures reach JNI as a pending exception and `abort()` the process. A
  /// 200 MP photograph is an ~800 MB allocation for a 40-point thumbnail, and
  /// an `OutOfMemoryError` in it is not something Dart can catch.
  ///
  /// Returns null on every other platform, and for any file that will not
  /// decode. A null is the caller's cue to draw whatever it draws for a picture
  /// it has not got — never a reason to fall back to `Image.file` on Android,
  /// which is the thing being avoided.
  static Future<StillFrame?> stillAt(
    String path, {
    required int maxEdge,
  }) async {
    if (maxEdge <= 0) {
      throw ArgumentError.value(maxEdge, 'maxEdge', 'must be positive');
    }
    if (!Platform.isAndroid) {
      return null;
    }
    final result = await _invokeMethod<Map<Object?, Object?>>('stillAt', {
      'path': path,
      'maxEdge': maxEdge,
    });
    if (result == null) {
      return null;
    }
    return StillFrame(
      rgba: result['rgba']! as Uint8List,
      width: result['width']! as int,
      height: result['height']! as int,
      format: result['format']! as String,
      colorSpace: result['colorSpace']! as String,
      hasGainmap: result['hasGainmap'] as bool? ?? false,
    );
  }

  static Future<ClipFrameOutcome> clipFrameAt(
    String path,
    Duration at, {
    int maxEdge = 512,
  }) async {
    if (maxEdge <= 0) {
      throw ArgumentError.value(maxEdge, 'maxEdge', 'must be positive');
    }
    final result = await _invokeMethod<Map<Object?, Object?>>('clipFrameAt', {
      'path': path,
      // Clamped rather than passed through. A negative instant is a caller
      // whose trim handle ran off the left edge, and both platforms would read
      // it as "before the first sample" anyway — answering with the first frame
      // is what the handle is pointing at.
      'atUs': at.inMicroseconds < 0 ? 0 : at.inMicroseconds,
      'maxEdge': maxEdge,
    });
    if (result == null) {
      return const ClipFrameNone();
    }
    // A map carrying the flag and no pixels. Checked before the cast below,
    // which would otherwise throw on the missing `rgba`.
    if (result['retired'] == true) {
      return const ClipFrameRetired();
    }
    return ClipFrameReady(
      ClipPreviewFrame(
        rgba: result['rgba'] as Uint8List,
        width: result['width'] as int,
        height: result['height'] as int,
      ),
    );
  }

  /// Closes every decoder [clipFrameAt] left open.
  ///
  /// **Required, not advisable.** A phone has a small, device-specific number
  /// of concurrent hardware video decoders — often one or two at 4K — and a
  /// held one is a decoder the next clip, or the next app, cannot have. Call it
  /// when the screen that was scrubbing goes away.
  ///
  /// The one failure it does *not* have to guard against is an export starting
  /// while previews are open: [setup] releases them itself, because "the film
  /// refused because a screen you already left was still holding a decoder" is
  /// not a thing anyone could diagnose.
  static Future<void> releaseClipPreviews() async {
    return await _invokeMethod('releaseClipPreviews');
  }

  /// append raw pcm audio samples
  ///  - 16 bit, little-endiant
  ///  - when using stereo audio, samples should be interleaved left channel first
  static Future<void> appendAudioFrame(Uint8List rawPcm) async {
    assert(rawPcm.length == (sampleRate * audioChannels * 2) / fps,
        "invalid data length");
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
        print(
            "[FQVE] '<$method>' rawRgba: ${arguments['rawRgba'].length} bytes");
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
      if (method == "clipFrameAt" && result is Map) {
        // **Summarized, because the whole thing is a frame.** The argument log
        // above already refuses to print `rawRgba`; the result log had no such
        // case because nothing used to answer with pixels. At the default log
        // level a single 512px preview printed 590 KB of decimal bytes, and a
        // `maxEdge` large enough to pass a 4K frame through printed enough to
        // take the run down with it — a diagnostic that kills the thing it is
        // diagnosing, in the one method that is called per scrub position.
        print(
          "[FQVE] <$method> result: "
          "${result['width']}x${result['height']}, "
          "${(result['rgba'] as Uint8List?)?.length ?? 0} bytes",
        );
      } else {
        print("[FQVE] <$method> result: $result");
      }
    }

    return result;
  }
}
