package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertTrue;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.File;
import java.nio.ByteBuffer;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Is Android's per-frame clip cost the codec, or the round trip around it?
 *
 * <p><b>The question CLIPS-UI-PLAN §7.2 asks and nothing has answered</b>, and
 * it decides whether the Clips screen's viewer can exist on Android at all.
 * §2 measured a 4K clip at 272.7 ms for a forward move inside the window and
 * 1524.5 ms backward on this phone, against 31.0 and 205.3 on a 2018 iPhone —
 * and, decisively, that cost tracks *footage traversed* rather than call count,
 * so 1x playback costs about 1x realtime at any display cadence and 3x is
 * arithmetically impossible. §7.2's suspicion, written before anyone measured
 * it: "4K10 on an S24 should not take ~27 ms of decode; a hardware decoder does
 * this far faster. Suspect the input-feed / output-dequeue / getOutputImage
 * cycle rather than the codec."
 *
 * <p><b>Three arms rather than the two §7.2 proposes</b>, because the same run
 * can also settle §7.1 — "which half of Android's per-call cost is which", so
 * far fitted from two `maxEdge` points rather than instrumented. Each arm adds
 * exactly one stage to the one before it, over the same decoder and the same
 * frames:
 *
 * <ul>
 *   <li><b>decode</b> — feed, dequeue, release. Never touches the pixels. This
 *       is the codec's own throughput and nothing else.
 *   <li><b>image</b> — the same, plus `getOutputImage()`. The difference is what
 *       acquiring the frame costs, which is the part §7.2 suspects.
 *   <li><b>copy</b> — the same, plus `ClipReader.copyPlane` over all three
 *       planes into `short[]`, which is what the plugin actually does.
 * </ul>
 *
 * <p>So <b>image − decode</b> prices the round trip, <b>copy − image</b> prices
 * the output handling, and <b>decode</b> alone says whether the hardware was
 * ever the problem. If decode comes out near realtime the fix is a tighter loop
 * — and a tighter loop is available in Java before it is worth reaching for the
 * NDK. If decode is already at 1x realtime, no amount of loop is going to make
 * a viewer, and §8's fallback stands.
 *
 * <p><b>The real corpus clip, not a synthetic one</b>, because a synthesized
 * gradient compresses to almost nothing and would flatter the decoder at
 * exactly the measurement that matters. Staged out of band, since an
 * instrumented test cannot read the app's own container:
 *
 * <pre>
 * ffmpeg -i spikes/photocorpus/HOVER_20260802_1785675410599.mp4 -t 12 -c copy clip4k10.mp4
 * adb push clip4k10.mp4 /data/local/tmp/clip4k10.mp4
 * </pre>
 *
 * <p>A stream copy, so the codec, the profile and the resolution are the
 * original's — HEVC Main 10, 3840x2160, yuv420p10le. **Skips loudly rather than
 * passing** when the file is absent: a green run that measured nothing is the
 * failure this whole file exists to avoid.
 */
@RunWith(AndroidJUnit4.class)
public class ClipDecodeSplitTest {

    private static final String TAG = "[FQVE-Android]";

    private static final String CLIP = "/data/local/tmp/clip4k10.mp4";

    /** Frames per arm after the warmup, ~4 s of footage at 30 fps. */
    private static final int MEASURED = 120;
    private static final int WARMUP = 10;

    private enum Arm {
        /** Feed, dequeue, release. The codec alone. */
        DECODE,
        /** Plus `getOutputImage()`. */
        IMAGE,
        /** Plus the plane copy the plugin really does. */
        COPY,
    }

    @Test
    public void whereAndroidsPerFrameClipCostActuallyGoes() throws Exception {
        final File clip = new File(CLIP);
        if (!clip.exists()) {
            // Loud, and not a pass. See the class comment for the two commands.
            Log.w(TAG, "CLIPSPLIT skipped — no clip at " + CLIP
                    + "; stage it with ffmpeg -c copy and adb push, or this"
                    + " test silently measures nothing");
            return;
        }

        final double decode = run(clip, Arm.DECODE);
        final double image = run(clip, Arm.IMAGE);
        final double copy = run(clip, Arm.COPY);

        // 30000/1001 fps: one frame is 33.37 ms of footage, and "1x realtime"
        // is the bar a viewer has to clear.
        final double frameMs = 1001.0 / 30.0;
        Log.i(TAG, String.format(
                "CLIPSPLIT 3840x2160 hevc-main10 frames=%d"
                        + " decode=%.1fms/%.2fx image=%.1fms/%.2fx copy=%.1fms/%.2fx"
                        + " roundtrip=%.1fms output=%.1fms",
                MEASURED,
                decode, decode / frameMs,
                image, image / frameMs,
                copy, copy / frameMs,
                image - decode, copy - image));

        // Not a threshold on the answer — the answer is the point and nobody
        // has grounds for a bar yet. This asserts the run happened and the
        // stages nest, so a green run cannot mean an arm that did nothing.
        assertTrue("decode arm measured nothing", decode > 0);
        assertTrue("adding getOutputImage made it faster (" + image + " vs "
                + decode + "ms), so the arms are not nested", image >= decode * 0.9);
        assertTrue("adding the plane copy made it faster (" + copy + " vs "
                + image + "ms), so the arms are not nested", copy >= image * 0.9);
    }

    /** Milliseconds per frame for one arm, median-free mean over [MEASURED]. */
    private double run(File clip, Arm arm) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(clip.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat candidate = extractor.getTrackFormat(i);
                final String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            assertTrue("no video track in " + clip, track >= 0);
            extractor.selectTrack(track);

            codec = MediaCodec.createDecoderByType(
                    format.getString(MediaFormat.KEY_MIME));
            // **No surface**, which is the configuration the plugin uses and the
            // only one that can hand back an `Image` at all. A surface decode
            // would be faster and would also answer a different question.
            codec.configure(format, null, null, 0);
            codec.start();

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[][] slot = null;
            long nanos = 0;
            int produced = 0;
            boolean fed = false;

            while (produced < WARMUP + MEASURED) {
                if (!fed) {
                    final int in = codec.dequeueInputBuffer(10_000L);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                final long started = System.nanoTime();
                final int out = codec.dequeueOutputBuffer(info, 10_000L);
                if (out < 0) {
                    // A format change or a timeout is not a frame; its cost
                    // belongs to nobody and is not counted.
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER && fed
                            && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                    continue;
                }

                if (arm != Arm.DECODE) {
                    final Image image = codec.getOutputImage(out);
                    if (image != null && arm == Arm.COPY) {
                        final Image.Plane[] planes = image.getPlanes();
                        final int w = image.getWidth();
                        final int h = image.getHeight();
                        if (slot == null) {
                            slot = new short[][]{
                                    new short[w * h],
                                    new short[(w / 2) * (h / 2)],
                                    new short[(w / 2) * (h / 2)],
                            };
                        }
                        final boolean tenBit = planes[0].getPixelStride() == 2;
                        ClipReader.copyPlane(planes[0].getBuffer(),
                                planes[0].getRowStride(), planes[0].getPixelStride(),
                                slot[0], 0, 0, w, h, tenBit, false);
                        ClipReader.copyPlane(planes[1].getBuffer(),
                                planes[1].getRowStride(), planes[1].getPixelStride(),
                                slot[1], 0, 0, w / 2, h / 2, tenBit, false);
                        ClipReader.copyPlane(planes[2].getBuffer(),
                                planes[2].getRowStride(), planes[2].getPixelStride(),
                                slot[2], 0, 0, w / 2, h / 2, tenBit, false);
                    }
                }
                codec.releaseOutputBuffer(out, false);
                final long spent = System.nanoTime() - started;

                produced++;
                if (produced > WARMUP) {
                    nanos += spent;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }

            final int counted = Math.max(1, produced - WARMUP);
            assertTrue(arm + " produced only " + produced + " frames",
                    produced > WARMUP);
            return nanos / (double) counted / 1e6;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // Reported by whatever threw first.
                }
                codec.release();
            }
            extractor.release();
        }
    }
}
