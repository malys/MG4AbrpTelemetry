package com.mg4.abrptelemetry;

/**
 * Decides whether a given scheduler tick should actually upload.
 *
 * The service ticks at a fixed short rate; this class thins the uploads out. A car parked
 * overnight and not charging does not need a sample every minute — that is wasted data and
 * power — but nothing may be lost while driving or charging, where ABRP needs the
 * resolution to keep a route plan accurate.
 *
 * Free of Android types so the policy can be unit-tested directly.
 */
final class UploadCadence {

    /**
     * Parked and not charging: one keep-alive sample every 15 minutes. The car is not
     * moving and its SOC is not changing, so anything more is spent for nothing.
     */
    static final long PARKED_INTERVAL_MS = 900_000L;

    private UploadCadence() { }

    /**
     * @param nowMs         current time
     * @param lastUploadMs  when the last upload was attempted, 0 if never
     * @param parked        null when the gear could not be read
     * @param charging      null when the charge port state could not be read
     * @param socPercent    null when the battery level could not be read
     * @param stateChanged  true if parked/charging differ from the previous tick
     * @param settings      user-configured cadence
     */
    static boolean shouldUpload(long nowMs, long lastUploadMs,
                                Boolean parked, Boolean charging, Integer socPercent,
                                boolean stateChanged, UploadSettings settings) {
        // Never uploaded yet: send immediately so ABRP sees the car come online.
        if (lastUploadMs <= 0L) return true;

        // A transition (just parked, plugged in, unplugged) is exactly what ABRP must
        // learn straight away — never make it wait for the slow interval.
        if (stateChanged) return true;

        return nowMs - lastUploadMs >= intervalFor(parked, charging, socPercent, settings);
    }

    /**
     * Interval that applies in the given state.
     *
     * Unknown state counts as active: if the gear or charge port could not be read we do
     * not know the car is idle, and under-reporting a moving car is worse than a few
     * extra requests from a stationary one.
     */
    static long intervalFor(Boolean parked, Boolean charging, Integer socPercent,
                            UploadSettings settings) {
        boolean idle = Boolean.TRUE.equals(parked) && Boolean.FALSE.equals(charging);
        if (idle) return PARKED_INTERVAL_MS;

        // Low battery: the driver is picking a charge stop and ABRP needs a tighter SOC
        // curve to get the arrival estimate right. Only while actually in use — a car
        // parked at 5 % is still parked and gains nothing from fast polling.
        if (settings.boostLowSoc && socPercent != null
                && socPercent <= settings.lowSocPercent) {
            return Math.min(settings.intervalMs(), UploadSettings.BOOSTED_INTERVAL_SEC * 1000L);
        }
        return settings.intervalMs();
    }
}
