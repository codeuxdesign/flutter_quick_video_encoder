package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Reading the phone's own account of whether it is throttling.
 *
 * <p>The reason this is worth a test rather than a line of code: the number it
 * reports is the one that decides whether two `PERF` rows are comparable, and a
 * thermal probe that silently returns a constant would make every row look
 * equally trustworthy.
 */
@RunWith(AndroidJUnit4.class)
public class ThermalWatchTest {

    private static final String TAG = "[FQVE-Android]";

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void thisDeviceAnswers() {
        final ThermalWatch watch = ThermalWatch.of(context());
        assertNotNull("no PowerManager on this device", watch);

        final int status = watch.currentStatus();
        Log.i(TAG, "THERMAL test read " + ThermalWatch.describe(status));

        assertTrue("thermal status " + status + " is outside the documented range",
                status >= PowerManager.THERMAL_STATUS_NONE
                        && status <= PowerManager.THERMAL_STATUS_SHUTDOWN);
    }

    /**
     * The threshold is a judgement, and it is the plugin's rather than the
     * caller's: LIGHT happens under any sustained load and warning about it
     * would only teach people to ignore the warning. MODERATE is where clocks
     * come down far enough to see in a frame time.
     */
    @Test
    public void onlyModerateAndAboveIsWorthTellingARiderAbout() {
        assertFalse(ThermalWatch.worthReporting(PowerManager.THERMAL_STATUS_NONE));
        assertFalse(ThermalWatch.worthReporting(PowerManager.THERMAL_STATUS_LIGHT));
        assertTrue(ThermalWatch.worthReporting(PowerManager.THERMAL_STATUS_MODERATE));
        assertTrue(ThermalWatch.worthReporting(PowerManager.THERMAL_STATUS_SEVERE));
        assertTrue(ThermalWatch.worthReporting(PowerManager.THERMAL_STATUS_CRITICAL));
        // A device that will not answer is not a device that is throttling.
        assertFalse(ThermalWatch.worthReporting(-1));
    }

    @Test
    public void everyStatusNamesItself() {
        assertEquals("NONE(0)",
                ThermalWatch.describe(PowerManager.THERMAL_STATUS_NONE));
        assertEquals("MODERATE(2)",
                ThermalWatch.describe(PowerManager.THERMAL_STATUS_MODERATE));
        assertEquals("SHUTDOWN(6)",
                ThermalWatch.describe(PowerManager.THERMAL_STATUS_SHUTDOWN));
        // The integer survives even when the name does not, so a future status
        // is still legible in a log.
        assertEquals("UNKNOWN(99)", ThermalWatch.describe(99));
    }

    /**
     * Sampling every frame must be nearly free, or the instrument changes what
     * it is measuring. Ten thousand samples stand in for a long export.
     */
    @Test
    public void samplingEveryFrameCostsNothingWorthHaving() {
        final ThermalWatch watch = ThermalWatch.of(context());
        assertNotNull(watch);

        final long started = System.nanoTime();
        for (int frame = 0; frame < 10_000; frame++) {
            watch.sample(frame);
        }
        final double perSampleMicros = (System.nanoTime() - started) / 10_000.0 / 1000.0;
        Log.i(TAG, String.format("THERMAL sample cost %.1fus", perSampleMicros));

        // A frame's budget is tens of milliseconds; anything under a hundred
        // microseconds is lost in the noise of one.
        assertTrue("sampling cost " + perSampleMicros + "us per frame",
                perSampleMicros < 100.0);
    }
}
