package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * The sampled preview gather against the full one it replaces.
 *
 * <p><b>The whole point of the change is that it is not an approximation.</b>
 * `ClipReader.samplePreview` gathers about 57,600 samples where `copyPlane`
 * gathered 8.3 million, and the only thing that makes that safe is picking
 * exactly the samples `ClipPreview.from` would have read out of the big one.
 * If it picks different ones the preview stops matching the film, and this
 * project has already learned — twice, in `FrameYuv`'s Rec.601 years and in
 * `ClipColor`'s sixteen codes — that a wrong color is invisible except against
 * a reference. So the reference is here: the full path, run on the same bytes.
 *
 * <p>Both 4:2:0 chroma positions matter, which is why the fixture is not flat.
 * A frame of one color agrees under any sampling at all and would make this
 * test green against an implementation that read the wrong chroma entirely.
 *
 * <p>Plain host JUnit: `ClipPreview` and `ClipFrame` have no android imports,
 * and `ClipReader.copyPlane` touches only `ByteBuffer`.
 */
public class ClipPreviewSampleTest {

    /** Big enough that 4:2:0 chroma and the scale factor both bite. */
    private static final int SRC_W = 96;
    private static final int SRC_H = 64;
    private static final int MAX_EDGE = 16;

    @Test
    public void samplingAgreesWithGatheringEverythingThenSampling() {
        for (final boolean fullRange : new boolean[]{false, true}) {
            final Planes planes = planes(SRC_W, SRC_H, 1);
            final ClipPreview.Image whole = viaFullGather(planes, fullRange);
            final ClipPreview.Image sampled = viaSampler(planes, fullRange);

            assertNotNull("full gather produced nothing", whole);
            assertNotNull("sampler produced nothing", sampled);
            assertEquals("width", whole.width, sampled.width);
            assertEquals("height", whole.height, sampled.height);
            assertArrayEquals("fullRange=" + fullRange + " pixels",
                    whole.rgba, sampled.rgba);
        }
    }

    /**
     * The same, with semiplanar chroma — the layout a real phone hands back.
     *
     * <p>A chroma pixel stride of 2 sends `copyPlane` down its other branch and
     * puts the two chroma channels in one interleaved buffer. The sampler
     * indexes by stride and should not care; asserting it rather than assuming
     * it is the whole lesson of `FrameYuv`, where the planar-only host tests
     * passed for a month while the phone took the other path.
     */
    @Test
    public void semiplanarChromaSamplesTheSameWay() {
        final Planes planes = planes(SRC_W, SRC_H, 2);
        final ClipPreview.Image whole = viaFullGather(planes, false);
        final ClipPreview.Image sampled = viaSampler(planes, false);
        assertArrayEquals("semiplanar pixels", whole.rgba, sampled.rgba);
    }

    /** And the sampled frame really is smaller, or nothing was saved. */
    @Test
    public void theSampledFrameIsTheOutputSizeRatherThanTheSourceSize() {
        final Planes planes = planes(SRC_W, SRC_H, 1);
        final ClipFrame frame = sampledFrame(planes, false);
        assertEquals("sampled width", MAX_EDGE, frame.width);
        assertTrue("sampled height", frame.height < SRC_H);
        assertEquals("one chroma sample per pixel", frame.width, frame.chromaWidth);
        assertEquals("and therefore no shift", 0, frame.chromaShift);
        assertTrue("luma should be far smaller than the source",
                frame.luma.length * 200 < SRC_W * SRC_H * 100);
    }

    /** What `copyImage` does today: gather it all, then sample. */
    private static ClipPreview.Image viaFullGather(Planes p, boolean fullRange) {
        final int cw = SRC_W / 2;
        final int ch = SRC_H / 2;
        final short[] luma = new short[SRC_W * SRC_H];
        final short[] cb = new short[cw * ch];
        final short[] cr = new short[cw * ch];
        ClipReader.copyPlane(p.y, p.yRow, p.yPx, luma, 0, 0, SRC_W, SRC_H, false, fullRange);
        ClipReader.copyPlane(p.u, p.uRow, p.uPx, cb, 0, 0, cw, ch, false, fullRange);
        ClipReader.copyPlane(p.v, p.vRow, p.vPx, cr, 0, 0, cw, ch, false, fullRange);
        final ClipFrame frame = new ClipFrame(luma, cb, cr, SRC_W, SRC_H,
                color(fullRange), 0L, false);
        return ClipPreview.from(frame, MAX_EDGE);
    }

    /** What it does now: gather only what will be read. */
    private static ClipPreview.Image viaSampler(Planes p, boolean fullRange) {
        return ClipPreview.from(sampledFrame(p, fullRange), MAX_EDGE);
    }

    /**
     * `samplePreview`'s arithmetic, reproduced here because the method is
     * private and takes an `android.media.Image.Plane[]` that a host test
     * cannot build. **This is the one weakness of this test and it is stated
     * rather than hidden**: it proves the sampling rule agrees with the full
     * gather, not that `ClipReader` implements this rule. `ClipDecodeSplitTest`
     * on a device is what exercises the real method.
     */
    private static ClipFrame sampledFrame(Planes p, boolean fullRange) {
        final int longest = Math.max(SRC_W, SRC_H);
        final int outW = ClipPreview.outputEdge(SRC_W, longest, MAX_EDGE);
        final int outH = ClipPreview.outputEdge(SRC_H, longest, MAX_EDGE);
        final short[] luma = new short[outW * outH];
        final short[] cb = new short[outW * outH];
        final short[] cr = new short[outW * outH];
        for (int y = 0; y < outH; y++) {
            final float v = (y + 0.5f) / outH;
            int sy = (int) (v * SRC_H);
            if (sy >= SRC_H) {
                sy = SRC_H - 1;
            }
            final int out = y * outW;
            for (int x = 0; x < outW; x++) {
                final float u = (x + 0.5f) / outW;
                int sx = (int) (u * SRC_W);
                if (sx >= SRC_W) {
                    sx = SRC_W - 1;
                }
                luma[out + x] = sample(p.y, p.yRow, p.yPx, sx, sy, fullRange);
                cb[out + x] = sample(p.u, p.uRow, p.uPx, sx >> 1, sy >> 1, fullRange);
                cr[out + x] = sample(p.v, p.vRow, p.vPx, sx >> 1, sy >> 1, fullRange);
            }
        }
        return new ClipFrame(luma, cb, cr, outW, outH, outW, outH, 0,
                color(fullRange), 0L, false);
    }

    private static short sample(ByteBuffer b, int rowStride, int pixelStride,
                                int x, int y, boolean fullRange) {
        return ClipFrame.widen(b.get(y * rowStride + x * pixelStride) & 0xFF, fullRange);
    }

    private static ClipColor color(boolean fullRange) {
        return new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR,
                fullRange);
    }

    private static final class Planes {
        ByteBuffer y;
        ByteBuffer u;
        ByteBuffer v;
        int yRow;
        int yPx;
        int uRow;
        int uPx;
        int vRow;
        int vPx;
    }

    /**
     * A frame whose every sample differs from its neighbours, so a sampler that
     * reads the wrong position cannot come out equal by luck.
     *
     * <p>`chromaPixelStride` of 1 is planar and 2 is semiplanar, where cb and cr
     * interleave in one buffer exactly as a phone's decoder hands them over.
     */
    private static Planes planes(int width, int height, int chromaPixelStride) {
        final Planes p = new Planes();
        final int cw = width / 2;
        final int chh = height / 2;
        p.yRow = width + 8;
        p.yPx = 1;
        final byte[] luma = new byte[p.yRow * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                luma[y * p.yRow + x] = (byte) (x * 5 + y * 13 + 7);
            }
        }
        p.y = ByteBuffer.wrap(luma);

        p.uRow = cw * chromaPixelStride + 8;
        p.uPx = chromaPixelStride;
        p.vRow = p.uRow;
        p.vPx = chromaPixelStride;
        if (chromaPixelStride == 1) {
            final byte[] cb = new byte[p.uRow * chh];
            final byte[] cr = new byte[p.vRow * chh];
            for (int y = 0; y < chh; y++) {
                for (int x = 0; x < cw; x++) {
                    cb[y * p.uRow + x] = (byte) (x * 11 + y * 3 + 29);
                    cr[y * p.vRow + x] = (byte) (x * 7 + y * 17 + 61);
                }
            }
            p.u = ByteBuffer.wrap(cb);
            p.v = ByteBuffer.wrap(cr);
        } else {
            final byte[] shared = new byte[p.uRow * chh];
            for (int y = 0; y < chh; y++) {
                for (int x = 0; x < cw; x++) {
                    shared[y * p.uRow + x * 2] = (byte) (x * 11 + y * 3 + 29);
                    shared[y * p.uRow + x * 2 + 1] = (byte) (x * 7 + y * 17 + 61);
                }
            }
            p.u = ByteBuffer.wrap(shared);
            p.v = ByteBuffer.wrap(shared, 1, shared.length - 1).slice();
        }
        return p;
    }
}
