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

    /**
     * Is the fill slow because it is a copy, or because of where it copies to?
     *
     * <p><b>The two answers have completely different fixes and the row cannot
     * tell them apart.</b> `fill_bulk=21.8ms` moves 3,110,400 bytes, which is
     * about 142 MB/s — ten to fifty times slower than a `memcpy` of that size
     * has any business being. Neither per-call overhead (2,160 bulk puts of
     * ~1.4 KB) nor throttling (1.8 GHz against 3.3 turns 3 ms into 5.5, not
     * 21.8) covers that. The suspicion is that a hardware codec's input planes
     * are uncached or write-combined ION memory, where byte-oriented writes run
     * at roughly this rate — and if that is true, **no cleverer copy touches
     * it**: not NDK, not SIMD, not fusing the convert into the fill. Only not
     * writing there, which means `createInputSurface` and a rewrite of this
     * path. If instead the two come out close, the copy is ordinary and a
     * native pass is worth half the win at a fraction of the risk.
     *
     * <p><b>The control is `allocateDirect`.</b> Same `fillPlanes`, same
     * strides, same sizes, same loop — a direct `ByteBuffer` either way, so the
     * Java path is identical and the only thing that differs is what the memory
     * is. Allocated once outside the loop, so neither allocation nor first-touch
     * faulting lands in the timer.
     *
     * <p><b>The order alternates.</b> Whichever fill runs second reads a source
     * array that the first one just walked, so a fixed order would hand it a
     * warm cache and quietly favor it. Even iterations go codec-first, odd ones
     * heap-first, and each accumulates separately.
     *
     * <p>No threshold is asserted, deliberately: the output of this is the
     * ratio, and inventing a bar before anybody has seen one would be a number
     * with nothing behind it. What is asserted is that it ran and that the bytes
     * really landed, so a green run cannot mean an elided loop.
     */
    @Test
    public void whetherTheFillCostIsTheDestination() throws Exception {
        final byte[] rgba = aFrame();
        final MediaCodec encoder = startEncoder();
        ByteBuffer heapY = null;
        ByteBuffer heapU = null;
        ByteBuffer heapV = null;
        try {
            byte[] scratch = FrameYuv.toYuv420Planar(rgba, WIDTH, HEIGHT, null);
            long codecNanos = 0;
            long heapNanos = 0;
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
                final int yRow = planes[0].getRowStride();
                final int yPx = planes[0].getPixelStride();
                final int uRow = planes[1].getRowStride();
                final int uPx = planes[1].getPixelStride();
                final int vRow = planes[2].getRowStride();
                final int vPx = planes[2].getPixelStride();

                if (heapY == null) {
                    // **Effective, not assumed.** `COLOR_FormatYUV420Flexible`
                    // is a family, and which member this encoder picked decides
                    // whether `fillPlanes` takes its bulk path at all — a
                    // chroma pixel stride of 2 is semiplanar NV12 and sends the
                    // chroma planes down the per-sample loop the bulk fix was
                    // supposed to have retired. `ClipReader` logs this for the
                    // decoder and nothing logged it for the encoder.
                    Log.i(TAG, String.format(
                            "FILLLAYOUT y(row=%d,px=%d) u(row=%d,px=%d) v(row=%d,px=%d)",
                            yRow, yPx, uRow, uPx, vRow, vPx));
                    // Matched to the codec's own capacities so the two fills
                    // write the same number of bytes to the same offsets.
                    heapY = ByteBuffer.allocateDirect(planes[0].getBuffer().capacity());
                    heapU = ByteBuffer.allocateDirect(planes[1].getBuffer().capacity());
                    heapV = ByteBuffer.allocateDirect(planes[2].getBuffer().capacity());
                    // Touched once, outside the timer, so page faults are not
                    // charged to the first measured iteration.
                    fillHeap(scratch, heapY, yRow, yPx, heapU, uRow, uPx, heapV, vRow, vPx);
                }

                long codec;
                long heap;
                if ((i & 1) == 0) {
                    long started = System.nanoTime();
                    FrameYuv.fillPlanes(scratch, WIDTH, HEIGHT,
                            planes[0].getBuffer(), yRow, yPx,
                            planes[1].getBuffer(), uRow, uPx,
                            planes[2].getBuffer(), vRow, vPx);
                    codec = System.nanoTime() - started;
                    started = System.nanoTime();
                    fillHeap(scratch, heapY, yRow, yPx, heapU, uRow, uPx, heapV, vRow, vPx);
                    heap = System.nanoTime() - started;
                } else {
                    long started = System.nanoTime();
                    fillHeap(scratch, heapY, yRow, yPx, heapU, uRow, uPx, heapV, vRow, vPx);
                    heap = System.nanoTime() - started;
                    started = System.nanoTime();
                    FrameYuv.fillPlanes(scratch, WIDTH, HEIGHT,
                            planes[0].getBuffer(), yRow, yPx,
                            planes[1].getBuffer(), uRow, uPx,
                            planes[2].getBuffer(), vRow, vPx);
                    codec = System.nanoTime() - started;
                }

                encoder.queueInputBuffer(index, 0,
                        encoder.getInputBuffer(index).capacity(),
                        i * 33_333L, 0);

                if (i >= WARMUP) {
                    codecNanos += codec;
                    heapNanos += heap;
                    measured++;
                }
            }

            assertTrue("no input buffers were measured", measured > 0);
            final double codec = codecNanos / (double) measured / 1e6;
            final double heap = heapNanos / (double) measured / 1e6;
            final int bytes = WIDTH * HEIGHT * 3 / 2;
            Log.i(TAG, String.format(
                    "FILLDEST %dx%d bytes=%d codec=%.1fms/%.0fMBps"
                            + " heap=%.1fms/%.0fMBps ratio=%.1fx",
                    WIDTH, HEIGHT, bytes,
                    codec, bytes / 1e6 / (codec / 1e3),
                    heap, bytes / 1e6 / (heap / 1e3),
                    heap == 0 ? 0 : codec / heap));

            // Not a threshold — a guard that the loop actually wrote something,
            // so "fast" cannot mean "elided".
            assertTrue("the heap fill wrote nothing", heapY.get(0) != 0
                    || heapY.get(1) != 0 || heapY.get(2) != 0);
        } finally {
            try {
                encoder.stop();
            } catch (Exception ignored) {
                // Reported by whatever threw first.
            }
            encoder.release();
        }
    }

    /** [FrameYuv.fillPlanes] against buffers of this test's own. */
    private static void fillHeap(byte[] scratch,
                                 ByteBuffer y, int yRow, int yPx,
                                 ByteBuffer u, int uRow, int uPx,
                                 ByteBuffer v, int vRow, int vPx) {
        FrameYuv.fillPlanes(scratch, WIDTH, HEIGHT, y, yRow, yPx,
                u, uRow, uPx, v, vRow, vPx);
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
