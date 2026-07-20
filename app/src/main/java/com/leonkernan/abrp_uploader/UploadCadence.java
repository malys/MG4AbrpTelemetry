package com.leonkernan.abrp_uploader;

/**
 * Decides whether a given scheduler tick should actually upload.
 *
 * The service ticks at a fixed rate; this class thins the uploads out. A car parked
 * overnight and not charging does not need a sample every 15 s — that is wasted data and
 * battery — but nothing may be lost while driving or charging, where ABRP needs the
 * resolution to keep a route plan accurate.
 *
 * Free of Android types so the policy can be unit-tested directly.
 */
final class UploadCadence {

    /** Full resolution while the car is doing something. */
    static final long ACTIVE_INTERVAL_MS = 15_000L;

    /** Parked and not charging: one keep-alive sample every 5 minutes. */
    static final long PARKED_INTERVAL_MS = 300_000L;

    private UploadCadence() { }

    /**
     * @param nowMs           current time
     * @param lastUploadMs    when the last upload was sent, 0 if never
     * @param parked          null when the gear could not be read
     * @param charging        null when the charge port state could not be read
     * @param stateChanged    true if parked/charging differ from the previous tick
     * @return true if this tick should upload
     */
    static boolean shouldUpload(long nowMs, long lastUploadMs,
                                Boolean parked, Boolean charging, boolean stateChanged) {
        // Never uploaded yet: send immediately so ABRP sees the car come online.
        if (lastUploadMs <= 0L) return true;

        // A transition (just parked, plugged in, unplugged) is exactly what ABRP must
        // learn straight away — never make it wait for the slow interval.
        if (stateChanged) return true;

        return nowMs - lastUploadMs >= intervalFor(parked, charging);
    }

    /**
     * Interval that applies in the given state.
     *
     * Unknown state counts as active: if the gear or charge port could not be read we do
     * not know the car is idle, and under-reporting a moving car is worse than a few
     * extra requests from a stationary one.
     */
    static long intervalFor(Boolean parked, Boolean charging) {
        boolean idle = Boolean.TRUE.equals(parked) && Boolean.FALSE.equals(charging);
        return idle ? PARKED_INTERVAL_MS : ACTIVE_INTERVAL_MS;
    }
}
