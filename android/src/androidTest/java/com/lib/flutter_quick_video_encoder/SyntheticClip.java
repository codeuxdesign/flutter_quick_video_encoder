package com.lib.flutter_quick_video_encoder;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Writes a small mp4 whose every frame is known by construction.
 *
 * <p><b>Synthesized rather than checked in.</b> A fixture would only prove the
 * reader agrees with whatever the fixture happens to contain; a clip built here
 * carries a different flat gray in every frame, so the frame the reader hands
 * back at any instant names itself. That is what turns "the clip advances" from
 * a thing you squint at into an assertion.
 *
 * <p>Flat frames also survive H.264 essentially intact, which keeps the test
 * about the reader rather than about the encoder's rate control — and they cost
 * three bytes to describe, so a 4K clip for the benchmark is as cheap to build
 * as a thumbnail.
 */
final class SyntheticClip {

    // Deliberately not a multiple of sixteen. H.264 codes in macroblocks, so a
    // decoder hands back a padded buffer and signals the real picture through a
    // crop rectangle — which means these numbers are what make the test exercise
    // `Image.getCropRect` and a row stride wider than the frame. At 320x240
    // everything lines up and the padding path never runs.
    static final int WIDTH = 300;
    static final int HEIGHT = 188;
    static final int FPS = 10;
    static final int FRAMES = 50;

    /** The gray of frame [index], spaced far enough apart to be unambiguous. */
    static int grayOf(int index) {
        return 30 + index * 4;
    }

    /** Which frame a decoded gray belongs to, or -1 if it is not one of ours. */
    static int frameOf(int gray) {
        final int index = Math.round((gray - 30) / 4.0f);
        if (index < 0 || index >= FRAMES) {
            return -1;
        }
        return Math.abs(grayOf(index) - gray) <= 2 ? index : -1;
    }

    private SyntheticClip() {
    }

    /** True when this device can encode [mime] at all, so a test can say why it skipped. */
    static boolean canEncode(String mime) {
        try {
            final MediaCodec probe = MediaCodec.createEncoderByType(mime);
            probe.release();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void write(File target, int standard, int transfer, int range) throws Exception {
        write(target, "video/avc", standard, transfer, range);
    }

    static void write(File target, String mime, int standard, int transfer, int range)
            throws Exception {
        write(target, mime, WIDTH, HEIGHT, FRAMES, standard, transfer, range);
    }

    /**
     * Encodes [frames] frames of flat gray into an mp4 at [target].
     *
     * <p>[standard], [transfer] and [range] are written into the encoder's format
     * so the reader has something to read back. Whether they survive the encoder
     * and the muxer is a property of the platform, not of this code, which is
     * exactly why a test asks rather than assumes.
     */
    static void write(File target, String mime, int width, int height, int frames,
                      int standard, int transfer, int range) throws Exception {
        final MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        // Every frame a keyframe, so a seek can land anywhere and a wrong seek
        // cannot be excused by the GOP.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, standard);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, range);
        writeWithFormat(target, mime, width, height, frames, format);
    }

    /**
     * As above, but encoded with a caller's format.
     *
     * <p>So a test can encode with **the plugin's own** `EncoderFormat` rather
     * than with values it typed itself. Passing the color tags in by hand
     * proves the platform round-trips them and proves nothing about whether this
     * encoder sets them — which is the half that actually regressed.
     */
    static void writeWithFormat(File target, String mime, int width, int height,
                                int frames, MediaFormat format) throws Exception {

        final MediaCodec encoder = MediaCodec.createEncoderByType(mime);
        MediaMuxer muxer = null;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(target.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            boolean started = false;
            int queued = 0;
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    final int index = encoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        if (queued == frames) {
                            encoder.queueInputBuffer(index, 0, 0,
                                    frameTimeUs(queued),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            fillGray(encoder, index, grayOf(queued), width, height);
                            encoder.queueInputBuffer(index, 0,
                                    encoder.getInputBuffer(index).capacity(),
                                    frameTimeUs(queued), 0);
                            queued++;
                        }
                    }
                }

                final int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    started = true;
                } else if (index >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && started) {
                        final ByteBuffer out = encoder.getOutputBuffer(index);
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(track, out, info);
                    }
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            muxer.stop();
        } finally {
            try {
                encoder.stop();
            } catch (Exception ignored) {
                // Already reported by whatever threw first.
            }
            encoder.release();
            if (muxer != null) {
                muxer.release();
            }
        }
    }

    /** The instant frame [index] starts, in the file's own timebase. */
    static long frameTimeUs(int index) {
        return index * 1_000_000L / FPS;
    }

    /**
     * Writes one flat gray into the encoder's input planes.
     *
     * <p>The three sample values come from {@link FrameYuv} once, on a
     * two-by-two frame, rather than by converting a full-size RGBA buffer — a 4K
     * frame that way is thirty-three megabytes of allocation per frame to
     * describe a single color.
     */
    private static void fillGray(MediaCodec encoder, int index, int gray,
                                 int width, int height) {
        final byte[] tiny = new byte[2 * 2 * 4];
        for (int i = 0; i < 4; i++) {
            tiny[i * 4] = (byte) gray;
            tiny[i * 4 + 1] = (byte) gray;
            tiny[i * 4 + 2] = (byte) gray;
            tiny[i * 4 + 3] = (byte) 255;
        }
        final byte[] yuv = FrameYuv.toYuv420Planar(tiny, 2, 2);

        final Image image = encoder.getInputImage(index);
        final Image.Plane[] planes = image.getPlanes();
        fillPlane(planes[0], yuv[0], width, height);
        fillPlane(planes[1], yuv[4], width / 2, height / 2);
        fillPlane(planes[2], yuv[5], width / 2, height / 2);
    }

    private static void fillPlane(Image.Plane plane, byte value, int width, int height) {
        final ByteBuffer buffer = plane.getBuffer();
        final int rowStride = plane.getRowStride();
        final int pixelStride = plane.getPixelStride();
        for (int y = 0; y < height; y++) {
            final int base = y * rowStride;
            for (int x = 0; x < width; x++) {
                buffer.put(base + x * pixelStride, value);
            }
        }
    }
}
