package com.lib.flutter_quick_video_encoder;

import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.File;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What a clip reader actually costs in resident memory, measured.
 *
 * <p><b>Measured rather than computed, because the arithmetic has been wrong
 * here before.</b> A 4K frame is 24.9 MB of `short[]` and there are two plane
 * slots per reader, so three readers works out near 149 MB — but this project
 * has already watched `ProcessInfo.maxRss` read a steady 337 MB right up to a
 * kill at 1850 MB. Instrument what the executioner counts. On Android that is
 * PSS, which is what `lowmemorykiller` scores against.
 *
 * <p>Reports rather than asserts a ceiling. The number that matters is the delta
 * against the same work at eight bits, and a threshold picked here would be a
 * guess about a phone nobody has tested yet.
 */
@RunWith(AndroidJUnit4.class)
public class ClipMemoryCostTest {

    private static final String TAG = "[FQVE-Android]";
    private static final String CLIP = "/data/local/tmp/hdr10bit.mp4";

    /** PSS in kilobytes, after giving the collector a chance to settle. */
    private static long settledPssKb() throws Exception {
        for (int i = 0; i < 3; i++) {
            Runtime.getRuntime().gc();
            Thread.sleep(120);
        }
        return Debug.getPss();
    }

    @Test
    public void reportsWhatAFourKReaderCostsResident() throws Exception {
        final File clip = new File(CLIP);
        Assume.assumeTrue("no 4K fixture at " + CLIP, clip.exists());

        final long before = settledPssKb();

        ClipReader reader = null;
        long holding = 0;
        try {
            reader = new ClipReader(CLIP);
            // One frame decoded is what allocates the plane slots; the reader
            // alone does not.
            final ClipFrame frame = reader.frameAtTime(0L);
            holding = settledPssKb();
            Log.i(TAG, "MEM one 4K reader " + frame.width + "x" + frame.height
                    + " tenBit=" + frame.tenBit
                    + " pssBefore=" + before + "kB"
                    + " pssHolding=" + holding + "kB"
                    + " delta=" + (holding - before) + "kB");
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        final long after = settledPssKb();
        Log.i(TAG, "MEM after close pss=" + after + "kB"
                + " retained=" + (after - before) + "kB");
    }
}
