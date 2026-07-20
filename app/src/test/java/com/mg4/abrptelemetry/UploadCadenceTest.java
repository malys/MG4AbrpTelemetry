package com.mg4.abrptelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Cadence policy. The head unit is awake for as long as the car is on, so every avoidable
 * upload and every avoidable GPS fix costs power — but nothing may be lost while driving,
 * charging, or running low on battery.
 */
public class UploadCadenceTest {

    private static final long T0 = 1_700_000_000_000L;

    private final UploadSettings defaults = UploadSettings.defaults();

    private UploadSettings settings(int intervalSec, boolean boost, int lowSoc) {
        return new UploadSettings(intervalSec, boost, lowSoc);
    }

    // ---- Basic cadence ----

    @Test
    public void theFirstUploadIsImmediate() {
        assertTrue(UploadCadence.shouldUpload(T0, 0L, true, false, 80, false, defaults));
    }

    @Test
    public void drivingUsesTheConfiguredInterval() {
        UploadSettings s = settings(60, false, 20);
        assertFalse(UploadCadence.shouldUpload(T0 + 59_000, T0, false, false, 80, false, s));
        assertTrue(UploadCadence.shouldUpload(T0 + 60_000, T0, false, false, 80, false, s));
    }

    @Test
    public void theDefaultIsOneMinute() {
        assertEquals(60, UploadSettings.DEFAULT_INTERVAL_SEC);
        assertEquals(60_000L, UploadCadence.intervalFor(false, false, 80, defaults));
    }

    @Test
    public void chargingKeepsTheActiveIntervalEvenWhenParked() {
        // Charging at a stop is exactly when ABRP needs a tight SOC curve.
        assertEquals(defaults.intervalMs(),
                UploadCadence.intervalFor(true, true, 50, defaults));
    }

    @Test
    public void parkedAndIdleBacksOffToFifteenMinutes() {
        assertEquals(900_000L, UploadCadence.intervalFor(true, false, 50, defaults));
        assertFalse(UploadCadence.shouldUpload(T0 + 899_000, T0, true, false, 50, false, defaults));
        assertTrue(UploadCadence.shouldUpload(T0 + 900_000, T0, true, false, 50, false, defaults));
    }

    @Test
    public void aStateChangeUploadsImmediately() {
        // Just parked, plugged in or unplugged: ABRP must not wait for the slow interval.
        assertTrue(UploadCadence.shouldUpload(T0 + 1_000, T0, true, false, 50, true, defaults));
    }

    @Test
    public void unknownStateCountsAsActive() {
        // An unreadable gear or charge port does not prove the car is idle.
        assertEquals(defaults.intervalMs(), UploadCadence.intervalFor(null, null, 80, defaults));
        assertEquals(defaults.intervalMs(), UploadCadence.intervalFor(true, null, 80, defaults));
        assertEquals(defaults.intervalMs(), UploadCadence.intervalFor(null, false, 80, defaults));
    }

    // ---- Low-SOC boost ----

    @Test
    public void lowSocBoostsTheCadence() {
        UploadSettings s = settings(300, true, 20);
        assertEquals(15_000L, UploadCadence.intervalFor(false, false, 19, s));
        assertEquals(15_000L, UploadCadence.intervalFor(false, false, 20, s));   // inclusive
    }

    @Test
    public void aboveTheThresholdThereIsNoBoost() {
        UploadSettings s = settings(300, true, 20);
        assertEquals(300_000L, UploadCadence.intervalFor(false, false, 21, s));
    }

    @Test
    public void theThresholdIsConfigurable() {
        UploadSettings s = settings(300, true, 35);
        assertEquals(15_000L, UploadCadence.intervalFor(false, false, 30, s));
        assertEquals(300_000L, UploadCadence.intervalFor(false, false, 40, s));
    }

    @Test
    public void theBoostCanBeDisabled() {
        UploadSettings s = settings(300, false, 20);
        assertEquals(300_000L, UploadCadence.intervalFor(false, false, 5, s));
    }

    @Test
    public void theBoostNeverSlowsAFasterConfiguredInterval() {
        // Configured 15 s with the boost on must not become slower at low SOC.
        UploadSettings s = settings(15, true, 20);
        assertEquals(15_000L, UploadCadence.intervalFor(false, false, 10, s));
    }

    @Test
    public void anUnreadableSocDoesNotBoost() {
        // No reading is not a low reading; boosting on unknown would poll fast forever.
        UploadSettings s = settings(300, true, 20);
        assertEquals(300_000L, UploadCadence.intervalFor(false, false, null, s));
    }

    @Test
    public void aParkedCarDoesNotBoostEvenAtLowSoc() {
        // Parked at 5 % and unplugged: still parked, still nothing to report.
        UploadSettings s = settings(60, true, 20);
        assertEquals(900_000L, UploadCadence.intervalFor(true, false, 5, s));
    }

    // ---- Measured effect ----

    @Test
    public void requestCountOverAnHourMatchesThePolicy() {
        // Counts exclude the baseline first upload, hence one short of the round number.
        assertEquals("parked and idle: one upload every 15 min",
                3, countUploadsOverAnHour(true, false, 50, defaults));
        assertEquals("driving at the default 60 s",
                59, countUploadsOverAnHour(false, false, 50, defaults));
        assertEquals("driving at low SOC: boosted to 15 s",
                239, countUploadsOverAnHour(false, false, 10, defaults));

        // The point of the exercise: the old fixed 15 s cadence sent 239 uploads an hour
        // in every state, including parked overnight.
        assertTrue(countUploadsOverAnHour(true, false, 50, defaults)
                < countUploadsOverAnHour(false, false, 50, defaults));
    }

    /** Simulates one hour of 15 s scheduler ticks with no state change. */
    private long countUploadsOverAnHour(Boolean parked, Boolean charging,
                                        Integer soc, UploadSettings settings) {
        long uploads = 0;
        long lastUpload = 0L;
        for (long tick = 0; tick < 3_600_000L; tick += 15_000L) {
            long now = T0 + tick;
            if (lastUpload == 0L) {          // ignore the immediate first upload
                lastUpload = now;
                continue;
            }
            if (UploadCadence.shouldUpload(now, lastUpload, parked, charging, soc,
                    false, settings)) {
                uploads++;
                lastUpload = now;
            }
        }
        return uploads;
    }
}
