package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.nio.ByteBuffer;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What it costs to hand one rendered frame to the encoder.
 *
 * <p>A full-quality film on a Galaxy S24 Ultra ran at 113.6 ms/frame against
 * 65.5 ms on a 2018 iPhone XR — a 2024 flagship losing to a six-year-old phone
 * by a factor of 1.7. PLAN's raster table accounts for about 13 ms of that. The
 * rest is on this path, which has no counterpart on Apple: there,
 * `createVideoSampleBuffer` shuffles RGBA into BGRA in C and the hardware
 * encoder does the color conversion itself. Here it is done in Java, twice —
 * once to convert, once to copy into the codec's planes.
 *
 * <p><b>Reported as an A/B.</b> The legacy per-sample fill is kept in this test
 * rather than in the plugin, so the two can be measured against each other on
 * the same buffers and the same device. An absolute figure on an unfamiliar
 * platform mostly measures the platform.
 */
@RunWith(AndroidJUnit4.class)
public class EncoderFeedCostTest {

    private static final String TAG = "[FQVE-Android]";

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int MEASURED = 20;
    private static final int WARMUP = 4;

    /** The fill as it was written: one bounds-checked put per sample. */
    private static void fillPerSample(Image.Plane[] planes, byte[] yuv420,
                                      int width, int height) {
        final ByteBuffer yBuffer = planes[0].getBuffer();
        final int yRowStride = planes[0].getRowStride();
        final int yPixelStride = planes[0].getPixelStride();
        int yOffset = 0;
        for (int i = 0; i < height; i++) {
            final int yPos = i * yRowStride;
            for (int j = 0; j < width; j++) {
                yBuffer.put(yPos + j * yPixelStride, yuv420[yOffset++]);
            }
        }
        final int chromaWidth = width / 2;
        final int chromaHeight = height / 2;
        int uOffset = width * height;
        final ByteBuffer uBuffer = planes[1].getBuffer();
        final int uRowStride = planes[1].getRowStride();
        final int uPixelStride = planes[1].getPixelStride();
        for (int i = 0; i < chromaHeight; i++) {
            final int uPos = i * uRowStride;
            for (int j = 0; j < chromaWidth; j++) {
                uBuffer.put(uPos + j * uPixelStride, yuv420[uOffset++]);
            }
        }
        int vOffset = width * height + chromaWidth * chromaHeight;
        final ByteBuffer vBuffer = planes[2].getBuffer();
        final int vRowStride = planes[2].getRowStride();
        final int vPixelStride = planes[2].getPixelStride();
        for (int i = 0; i < chromaHeight; i++) {
            final int vPos = i * vRowStride;
            for (int j = 0; j < chromaWidth; j++) {
                vBuffer.put(vPos + j * vPixelStride, yuv420[vOffset++]);
            }
        }
    }

    private static byte[] aFrame() {
        final byte[] rgba = new byte[WIDTH * HEIGHT * 4];
        // Not flat: a gradient, so nothing downstream can shortcut on it.
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                final int p = (y * WIDTH + x) * 4;
                rgba[p] = (byte) (x & 0xFF);
                rgba[p + 1] = (byte) (y & 0xFF);
                rgba[p + 2] = (byte) ((x + y) & 0xFF);
                rgba[p + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    private static MediaCodec startEncoder() throws Exception {
        final MediaFormat format =
                MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        final MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }

    /**
     * The two halves of the feed, timed separately so the fix can be aimed.
     *
     * <p>The conversion is arithmetic over two million pixels and is irreducible
     * short of changing where it runs. The fill is a copy, and a copy that costs
     * anything like the conversion is a copy done wrong.
     */
    @Test
    public void feedingOneFrameIsAffordable() throws Exception {
        final byte[] rgba = aFrame();
        final MediaCodec encoder = startEncoder();
        try {
            byte[] scratch = null;
            long convertNanos = 0;
            long bulkNanos = 0;
            long perSampleNanos = 0;
            int measured = 0;

            for (int i = 0; i < WARMUP + MEASURED; i++) {
                final int index = encoder.dequeueInputBuffer(1_000_000L);
                if (index < 0) {
                    continue;
                }
                final Image image = encoder.getInputImage(index);
                if (image == null) {
                    encoder.queueInputBuffer(index, 0, 0, 0, 0);
                    continue;
                }
                final Image.Plane[] planes = image.getPlanes();

                long started = System.nanoTime();
                scratch = FrameYuv.toYuv420Planar(rgba, WIDTH, HEIGHT, scratch);
                final long converted = System.nanoTime() - started;

                started = System.nanoTime();
                FrameYuv.fillPlanes(scratch, WIDTH, HEIGHT,
                        planes[0].getBuffer(), planes[0].getRowStride(),
                        planes[0].getPixelStride(),
                        planes[1].getBuffer(), planes[1].getRowStride(),
                        planes[1].getPixelStride(),
                        planes[2].getBuffer(), planes[2].getRowStride(),
                        planes[2].getPixelStride());
                final long bulk = System.nanoTime() - started;

                started = System.nanoTime();
                fillPerSample(planes, scratch, WIDTH, HEIGHT);
                final long perSample = System.nanoTime() - started;

                encoder.queueInputBuffer(index, 0,
                        encoder.getInputBuffer(index).capacity(),
                        i * 33_333L, 0);

                if (i >= WARMUP) {
                    convertNanos += converted;
                    bulkNanos += bulk;
                    perSampleNanos += perSample;
                    measured++;
                }
            }

            assertTrue("no input buffers were measured", measured > 0);
            final double convert = convertNanos / (double) measured / 1e6;
            final double bulk = bulkNanos / (double) measured / 1e6;
            final double perSample = perSampleNanos / (double) measured / 1e6;
            Log.i(TAG, String.format(
                    "FEED %dx%d convert=%.1fms fill_bulk=%.1fms fill_per_sample=%.1fms"
                            + " saved=%.1fms",
                    WIDTH, HEIGHT, convert, bulk, perSample, perSample - bulk));

            assertTrue("the bulk fill (" + bulk + "ms) should not be slower than"
                    + " the per-sample fill (" + perSample + "ms)", bulk <= perSample);
        } finally {
            try {
                encoder.stop();
            } catch (Exception ignored) {
                // Reported by whatever threw first.
            }
            encoder.release();
        }
    }

    /** Speed is only worth having if both fills put the same bytes down. */
    @Test
    public void bothFillsAgreeByteForByte() {
        final int width = 64;
        final int height = 32;
        final byte[] yuv = new byte[width * height * 3 / 2];
        for (int i = 0; i < yuv.length; i++) {
            yuv[i] = (byte) (i * 7 + 13);
        }
        // A padded, planar layout of the kind an encoder hands back.
        final int rowStride = 96;
        final ByteBuffer bulkY = ByteBuffer.allocate(rowStride * height);
        final ByteBuffer bulkU = ByteBuffer.allocate(rowStride * height / 2);
        final ByteBuffer bulkV = ByteBuffer.allocate(rowStride * height / 2);
        FrameYuv.fillPlanes(yuv, width, height,
                bulkY, rowStride, 1, bulkU, rowStride, 1, bulkV, rowStride, 1);

        final ByteBuffer refY = ByteBuffer.allocate(rowStride * height);
        final ByteBuffer refU = ByteBuffer.allocate(rowStride * height / 2);
        final ByteBuffer refV = ByteBuffer.allocate(rowStride * height / 2);
        int offset = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                refY.put(row * rowStride + col, yuv[offset++]);
            }
        }
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                refU.put(row * rowStride + col, yuv[offset++]);
            }
        }
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                refV.put(row * rowStride + col, yuv[offset++]);
            }
        }

        assertArrayEquals("luma", refY.array(), bulkY.array());
        assertArrayEquals("cb", refU.array(), bulkU.array());
        assertArrayEquals("cr", refV.array(), bulkV.array());
    }
}
