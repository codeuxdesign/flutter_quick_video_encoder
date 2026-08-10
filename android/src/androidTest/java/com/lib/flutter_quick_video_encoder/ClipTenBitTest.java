package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.File;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The 10-bit decode path, against a real HDR file rather than a synthetic one.
 *
 * <p><b>Why a pushed file and not a clip this test encodes itself.</b>
 * Everything else here synthesizes its input, which is the right default — a
 * fixture only proves the reader agrees with the fixture. Ten-bit is the one
 * case where that reasoning inverts: the thing under test *is* the layout a real
 * decoder chooses for a real stream, and an encoder driven by this process would
 * hand back whatever layout this process asked for. The bugs live in 4K HEVC
 * Main10 from an actual camera, so that is what this reads.
 *
 * <p>Push it first, then run:
 *
 * <pre>
 *   adb push hdr10bit.mp4 /data/local/tmp/hdr10bit.mp4
 * </pre>
 *
 * <p>Skips loudly when the file is absent rather than passing, because a 10-bit
 * test that silently becomes a no-op is worse than no test — it turns an
 * unexercised path into a green one.
 */
@RunWith(AndroidJUnit4.class)
public class ClipTenBitTest {

    private static final String TAG = "ClipTenBitTest";
    private static final String CLIP = "/data/local/tmp/hdr10bit.mp4";

    @Test
    public void aTenBitHdrClipDecodesThroughTheP010Path() throws Exception {
        final File clip = new File(CLIP);
        Assume.assumeTrue(
                "no 10-bit fixture at " + CLIP + " — push one to exercise this path",
                clip.exists());

        ClipReader reader = null;
        try {
            reader = new ClipReader(CLIP);
            final ClipFrame frame = reader.frameAtTime(0L);
            assertNotNull("10-bit clip decoded no frame at all", frame);

            // **The assertion that makes this test about ten bits.** A 10-bit
            // source does not force a 10-bit image: the decoder may hand back
            // YUV_420_888, having truncated on the way, and every colour below
            // would still look entirely reasonable. Without this line the test
            // passes while `copyPlane`'s 10-bit branch never runs — which is
            // precisely the state this test was written to end.
            assertTrue("decoder returned 8-bit YUV_420_888 for a Main10 stream, so the "
                            + "P010 branch did not execute and this test proves nothing",
                    frame.tenBit);

            assertEquals(3840, frame.width);
            assertEquals(2160, frame.height);

            // Real footage, so the exact colours are not knowable here. What is
            // knowable: a frame that decoded is not a frame of one flat value.
            // A P010 plane read with an 8-bit stride collapses to noise or to a
            // constant, and both fail this.
            int min = 255;
            int max = 0;
            int distinct = 0;
            final java.util.HashSet<Integer> seen = new java.util.HashSet<>();
            for (int y = 0; y < frame.height; y += 137) {
                for (int x = 0; x < frame.width; x += 149) {
                    final int rgb = frame.rgbAt(x, y);
                    seen.add(rgb);
                    final int luma = ((rgb >> 16) & 0xFF);
                    min = Math.min(min, luma);
                    max = Math.max(max, luma);
                }
            }
            distinct = seen.size();
            Log.i(TAG, "TENBIT 3840x2160 distinct=" + distinct
                    + " redMin=" + min + " redMax=" + max);

            assertTrue("every sampled pixel was identical (" + distinct
                    + " distinct) — the plane was not read as P010", distinct > 8);
            assertTrue("no tonal range at all: red " + min + ".." + max,
                    max - min > 8);
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }
}
