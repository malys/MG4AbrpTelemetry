package com.mg4.abrptelemetry;

import android.content.SharedPreferences;

/**
 * User-configurable upload policy.
 *
 * Defaults are deliberately conservative. The head unit is awake whenever the car is on,
 * and both the upload tick and the GPS subscription cost power the whole time, so the
 * default cadence is the slowest one that still keeps an ABRP route plan usable.
 */
final class UploadSettings {

    static final String KEY_INTERVAL_SEC    = "upload_interval_sec";
    static final String KEY_BOOST_LOW_SOC   = "boost_low_soc";
    static final String KEY_LOW_SOC_PERCENT = "low_soc_percent";

    /**
     * Default cadence while driving. 60 s is roughly 1.5 km between points at motorway
     * speed, which ABRP can still track; going much slower makes the live plan lag
     * reality. Parked and idle backs off far further on its own — see UploadCadence.
     */
    static final int DEFAULT_INTERVAL_SEC = 60;

    /** Offered in the UI. Kept short so the list stays a single glance on a car screen. */
    static final int[] INTERVAL_CHOICES_SEC = { 15, 30, 60, 120, 300 };

    static final boolean DEFAULT_BOOST_LOW_SOC = true;

    /** Below this, range planning gets tight and ABRP needs a finer SOC curve. */
    static final int DEFAULT_LOW_SOC_PERCENT = 20;

    /** Cadence used while SOC is low and the boost is enabled. */
    static final int BOOSTED_INTERVAL_SEC = 15;

    final int intervalSec;
    final boolean boostLowSoc;
    final int lowSocPercent;

    UploadSettings(int intervalSec, boolean boostLowSoc, int lowSocPercent) {
        this.intervalSec = intervalSec;
        this.boostLowSoc = boostLowSoc;
        this.lowSocPercent = lowSocPercent;
    }

    static UploadSettings from(SharedPreferences prefs) {
        return new UploadSettings(
                prefs.getInt(KEY_INTERVAL_SEC, DEFAULT_INTERVAL_SEC),
                prefs.getBoolean(KEY_BOOST_LOW_SOC, DEFAULT_BOOST_LOW_SOC),
                prefs.getInt(KEY_LOW_SOC_PERCENT, DEFAULT_LOW_SOC_PERCENT));
    }

    static UploadSettings defaults() {
        return new UploadSettings(
                DEFAULT_INTERVAL_SEC, DEFAULT_BOOST_LOW_SOC, DEFAULT_LOW_SOC_PERCENT);
    }

    long intervalMs() {
        return intervalSec * 1000L;
    }
}
