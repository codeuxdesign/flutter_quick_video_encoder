package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaFormat;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What one composited frame costs on this device.
 *
 * <p>The compositor decodes on the platform thread inside `appendVideoFrame`,
 * and for a wide-gamut or HDR source it converts color per destination pixel in
 * Java. Both were unmeasured, and "unmeasured" on the frame loop is how a phase
 * gets called done and then takes an hour to export.
 *
 * <p><b>Reported as a difference, not an absolute.</b> The same 4K source is
 * composited twice into the same rectangle, once tagged Rec.709 and once tagged
 * BT.2020 with an HLG transfer. Everything else is held: the same destination,
 * the same decoder, the same sampling. The gap between the two is the cost of
 * the float pipeline and nothing else, which is the number that would decide
 * whether it needs to move off the platform thread or into a coarser table. An
 * absolute figure on an unfamiliar platform mostly measures the platform.
 */
@RunWith(AndroidJUnit4.class)
public class ClipCompositeCostTest {

    private static final String TAG = "[FQVE-Android]";

    /** The vertical export target, which is the shape a phone actually renders. */
    private static final int FRAME_WIDTH = 1080;
    private static final int FRAME_HEIGHT = 1920;

    private static final int SOURCE_WIDTH = 3840;
    private static final int SOURCE_HEIGHT = 2160;
    private static final int SOURCE_FRAMES = 24;

    /** Frames timed, after a warm-up that is thrown away. */
    private static final int MEASURED = 20;
    private static final int WARMUP = 4;

    @Test
    public void compositingAFourKClipIsAffordable() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String mime = SyntheticClip.canEncode(MediaFormat.MIMETYPE_VIDEO_HEVC)
                ? MediaFormat.MIMETYPE_VIDEO_HEVC : "video/avc";

        final double rec709 = costOf(context, mime, "cost-rec709.mp4",
                MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        final double hlg = costOf(context, mime, "cost-hlg.mp4",
                MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_TRANSFER_HLG);

        Log.i(TAG, String.format(
                "COST %s %dx%d source=%dx%d hole=%dx%d rec709=%.1fms hlg=%.1fms delta=%.1fms",
                mime, FRAME_WIDTH, FRAME_HEIGHT, SOURCE_WIDTH, SOURCE_HEIGHT,
                FRAME_WIDTH, FRAME_HEIGHT, rec709, hlg, hlg - rec709));

        // A frame's own render budget is tens of milliseconds; a compositor that
        // costs more than a quarter of a second per frame would make the clip
        // seconds of a film cost more than the rest of it put together. This is a
        // ceiling on absurdity rather than a target — the number in the log is
        // what anyone should actually read.
        assertTrue("compositing one full-frame 4K clip took " + hlg + "ms", hlg < 250.0);
    }

    /** Milliseconds per composited frame, decode included, warm-up discarded. */
    private static double costOf(Context context, String mime, String name,
                                 int standard, int transfer) throws Exception {
        final File clip = new File(context.getCacheDir(), name);
        try {
            SyntheticClip.write(clip, mime, SOURCE_WIDTH, SOURCE_HEIGHT, SOURCE_FRAMES,
                    standard, transfer, MediaFormat.COLOR_RANGE_LIMITED);

            final byte[] rgba = new byte[FRAME_WIDTH * FRAME_HEIGHT * 4];
            // A fully cleared hole over the whole frame: the most work the blend
            // can be asked to do, since an opaque pixel is skipped before any
            // color conversion happens.
            final ClipCompositor compositor = new ClipCompositor();
            try {
                long nanos = 0;
                for (int i = 0; i < WARMUP + MEASURED; i++) {
                    java.util.Arrays.fill(rgba, (byte) 0);
                    final long sourceTimeUs =
                            SyntheticClip.frameTimeUs(i % SOURCE_FRAMES);
                    final List<Object> holes = holesFor(clip, sourceTimeUs);

                    final long started = System.nanoTime();
                    compositor.fill(rgba, FRAME_WIDTH, FRAME_HEIGHT, holes);
                    final long took = System.nanoTime() - started;

                    if (i >= WARMUP) {
                        nanos += took;
                    }
                }
                return nanos / (double) MEASURED / 1_000_000.0;
            } finally {
                compositor.release();
            }
        } finally {
            clip.delete();
        }
    }

    private static List<Object> holesFor(File clip, long sourceTimeUs) {
        final Map<String, Object> hole = new HashMap<>();
        hole.put("path", clip.getAbsolutePath());
        hole.put("sourceTimeUs", sourceTimeUs);
        hole.put("x", 0);
        hole.put("y", 0);
        hole.put("w", FRAME_WIDTH);
        hole.put("h", FRAME_HEIGHT);
        hole.put("quarterTurns", 0);
        final List<Object> holes = new ArrayList<>();
        holes.add(hole);
        return holes;
    }
}
