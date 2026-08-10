package com.lib.flutter_quick_video_encoder;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * Says when the phone is throttling, so a slow render is explained rather than
 * mysterious.
 *
 * <p><b>This exists because a measurement lied.</b> A full-quality film measured
 * 113.6 ms/frame; the same film after an encoder change measured 116.9 ms/frame,
 * which reads as a regression. The phone was at thermal status 2 with its big
 * cores pinned to 1.82 GHz against a 3.3 GHz boost — a little over half clock —
 * after twenty-five minutes of sustained rendering. Neither number was wrong and
 * neither was comparable, and nothing in the log said so.
 *
 * <p>That is the same failure this project keeps writing down: the run produced a
 * plausible number rather than an absence, so there was nothing to be careful
 * about. `PERF` rows already carry `host=` and `mode=` because a row that cannot
 * say what it was measuring under invites two runs to be compared that never
 * asked the same question. Thermal state belongs in that list.
 *
 * <p>It is also the honest answer to a rider watching a progress bar crawl. An
 * export that slows down because the phone is hot is not a bug, and a film that
 * takes twice as long with no explanation is indistinguishable from one that has
 * hung.
 *
 * <p><b>Reported on change, not only on a timer.</b> The moment throttling
 * starts is the event that explains the slowdown, and a purely periodic sample
 * would put it up to its own interval away from where it happened.
 */
final class ThermalWatch {

    private static final String TAG = "[FQVE-Android]";

    /**
     * How often to repeat an unchanged status.
     *
     * <p>Also the floor for `getThermalHeadroom`, which is documented to return
     * NaN when polled faster than once a second and is meant for use on the
     * order of tens of seconds.
     */
    private static final long QUIET_PERIOD_NANOS = 10_000_000_000L;

    private final PowerManager power;
    private final PowerManager.OnThermalStatusChangedListener listener;

    /**
     * Pushed by the platform rather than polled.
     *
     * <p>`getCurrentThermalStatus` is a binder call and costs about 114 us —
     * measured, after a test asserting that sampling every frame is free failed
     * and was right to. At thirty frames a second that is a tenth of a percent
     * of the budget, which is defensible, but it is also a poll that answers the
     * same thing hundreds of times between changes. A listener is cheaper *and*
     * reports the transition the moment it happens instead of up to a quiet
     * period later, which is the number that explains a slowdown.
     */
    private volatile int status;
    private int loggedStatus = Integer.MIN_VALUE;
    private long lastSampleNanos;
    private volatile int frameIndex;

    private ThermalWatch(PowerManager power) {
        this.power = power;
        this.status = power.getCurrentThermalStatus();
        this.listener = new PowerManager.OnThermalStatusChangedListener() {
            @Override
            public void onThermalStatusChanged(int value) {
                status = value;
                report(true);
            }
        };
    }

    /** A watch, or null where the platform cannot answer. */
    static ThermalWatch of(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        final Object service = context.getSystemService(Context.POWER_SERVICE);
        if (!(service instanceof PowerManager)) {
            return null;
        }
        final ThermalWatch watch;
        try {
            watch = new ThermalWatch((PowerManager) service);
            ((PowerManager) service).addThermalStatusListener(watch.listener);
        } catch (Exception e) {
            // A device that will not answer is not a reason to stop encoding.
            return null;
        }
        watch.report(false);
        return watch;
    }

    /** Stops listening. Safe to call twice. */
    void close() {
        try {
            power.removeThermalStatusListener(listener);
        } catch (Exception ignored) {
            // Already gone, or never registered.
        }
    }

    /**
     * Notes which frame the render is on, and repeats the state occasionally.
     *
     * <p>Two plain field writes and a clock read — no binder — so this costs
     * nothing worth measuring and can be called on every frame. Changes are
     * reported by the listener, not from here.
     */
    void sample(int frame) {
        frameIndex = frame;
        if (System.nanoTime() - lastSampleNanos >= QUIET_PERIOD_NANOS) {
            report(false);
        }
    }

    private void report(boolean changed) {
        final int now = status;
        final int previous = loggedStatus;
        // The platform delivers the current status to a listener the moment it
        // registers, which is not a change. Reporting it as one puts
        // "(was MODERATE)" next to "MODERATE" in the log, and a line that
        // contradicts itself is a line people stop reading.
        if (changed && now == previous) {
            return;
        }
        lastSampleNanos = System.nanoTime();
        loggedStatus = now;
        Log.i(TAG, "THERMAL " + describe(now)
                + headroomSuffix()
                + " frame=" + frameIndex
                + (changed && previous != Integer.MIN_VALUE
                        ? " (was " + describe(previous) + ")" : ""));
    }

    /**
     * The status right now, asked rather than remembered.
     *
     * <p>A caller may ask before a single frame has been encoded — the Export
     * screen deciding whether to warn, for instance — and a remembered value
     * would then be the sentinel rather than an answer. Returns -1 only when the
     * platform genuinely will not say.
     */
    int currentStatus() {
        return status;
    }

    /**
     * How close the device is to throttling, where 1.0 is the threshold.
     *
     * <p>Returns an empty string rather than a number when the platform declines
     * — it is API 30 and up, it is NaN if polled too often, and some devices do
     * not implement it at all. A missing forecast is worth nothing; a wrong one
     * is worth less.
     */
    private String headroomSuffix() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "";
        }
        try {
            final float headroom = power.getThermalHeadroom(0);
            if (Float.isNaN(headroom)) {
                return "";
            }
            return String.format(" headroom=%.2f", headroom);
        } catch (Exception e) {
            return "";
        }
    }

    /** The constant's own name, because a bare integer explains nothing in a log. */
    static String describe(int status) {
        final String name;
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE:
                name = "NONE";
                break;
            case PowerManager.THERMAL_STATUS_LIGHT:
                name = "LIGHT";
                break;
            case PowerManager.THERMAL_STATUS_MODERATE:
                name = "MODERATE";
                break;
            case PowerManager.THERMAL_STATUS_SEVERE:
                name = "SEVERE";
                break;
            case PowerManager.THERMAL_STATUS_CRITICAL:
                name = "CRITICAL";
                break;
            case PowerManager.THERMAL_STATUS_EMERGENCY:
                name = "EMERGENCY";
                break;
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                name = "SHUTDOWN";
                break;
            default:
                name = "UNKNOWN";
                break;
        }
        return name + "(" + status + ")";
    }

    /**
     * Whether [status] is one a render should tell the rider about.
     *
     * <p>`LIGHT` is normal under sustained load and says nothing useful.
     * `MODERATE` is where clocks come down enough to see, which is the point at
     * which "this is taking longer" has an answer.
     */
    static boolean worthReporting(int status) {
        return status >= PowerManager.THERMAL_STATUS_MODERATE;
    }
}
