package com.leonkernan.abrp_uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [T-919] Uploading every 15 s while the car sits parked overnight wastes data and power.
 * Backing off must never cost resolution while driving or charging.
 */
public class UploadCadenceTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void theFirstUploadIsImmediate() {
        assertTrue(UploadCadence.shouldUpload(T0, 0L, true, false, false));
    }

    @Test
    public void drivingKeepsFullResolution() {
        // Parked=false: 15 s cadence, not one tick later.
        assertFalse(UploadCadence.shouldUpload(T0 + 14_000, T0, false, false, false));
        assertTrue(UploadCadence.shouldUpload(T0 + 15_000, T0, false, false, false));
    }

    @Test
    public void chargingKeepsFullResolutionEvenWhenParked() {
        // Charging at a stop is exactly when ABRP needs a tight SOC curve.
        assertTrue(UploadCadence.shouldUpload(T0 + 15_000, T0, true, true, false));
        assertEquals(UploadCadence.ACTIVE_INTERVAL_MS,
                UploadCadence.intervalFor(true, true));
    }

    @Test
    public void parkedAndIdleBacksOff() {
        assertFalse(UploadCadence.shouldUpload(T0 + 15_000, T0, true, false, false));
        assertFalse(UploadCadence.shouldUpload(T0 + 299_000, T0, true, false, false));
        assertTrue(UploadCadence.shouldUpload(T0 + 300_000, T0, true, false, false));
    }

    @Test
    public void aStateChangeUploadsImmediately() {
        // Just parked, plugged in or unplugged: ABRP must not wait 5 minutes to learn it.
        assertTrue(UploadCadence.shouldUpload(T0 + 1_000, T0, true, false, true));
    }

    @Test
    public void unknownStateCountsAsActive() {
        // An unreadable gear or charge port does not prove the car is idle, and
        // under-reporting a moving car is worse than a few extra requests.
        assertEquals(UploadCadence.ACTIVE_INTERVAL_MS, UploadCadence.intervalFor(null, null));
        assertEquals(UploadCadence.ACTIVE_INTERVAL_MS, UploadCadence.intervalFor(true, null));
        assertEquals(UploadCadence.ACTIVE_INTERVAL_MS, UploadCadence.intervalFor(null, false));
        assertTrue(UploadCadence.shouldUpload(T0 + 15_000, T0, null, null, false));
    }

    @Test
    public void parkedIdleIsTheOnlySlowState() {
        assertEquals(UploadCadence.PARKED_INTERVAL_MS, UploadCadence.intervalFor(true, false));
        assertEquals(UploadCadence.ACTIVE_INTERVAL_MS, UploadCadence.intervalFor(false, false));
    }

    /** The acceptance criterion, expressed as arithmetic over one parked hour. */
    @Test
    public void requestCountDropsWhileParked() {
        long parkedRequests = countUploadsOverAnHour(true, false);
        long drivingRequests = countUploadsOverAnHour(false, false);
        assertEquals("driving must stay at 15 s resolution", 240, drivingRequests);
        assertEquals("parked and idle should back off to 5 min", 12, parkedRequests);
        assertTrue(parkedRequests < drivingRequests);
    }

    /** Simulates one hour of 15 s scheduler ticks with no state change. */
    private long countUploadsOverAnHour(Boolean parked, Boolean charging) {
        long uploads = 0;
        long lastUpload = 0L;
        for (long tick = 0; tick < 3_600_000L; tick += 15_000L) {
            long now = T0 + tick;
            if (UploadCadence.shouldUpload(now, lastUpload, parked, charging, false)) {
                uploads++;
                lastUpload = now;
            }
        }
        return uploads;
    }
}
