package com.leonkernan.abrp_uploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Telemetry payload construction — pure JVM, no car and no Android needed. */
public class TelemetryPayloadTest {

    /** Full car link, no location. */
    private String carOnly() {
        return TelemetryPayload.build(1_700_000_000L, true, 63, 48.4f, 210,
                12f, -14.5f, false, false, false, 21.5f,
                null, null, null, null);
    }

    @Test
    public void utcIsAlwaysPresent() {
        String noCarNoLoc = TelemetryPayload.build(1_700_000_000L, false, 0, 0f, 0,
                0f, 0f, false, false, false, 0f, null, null, null, null);
        assertEquals("{\"utc\":1700000000}", noCarNoLoc);
    }

    @Test
    public void carDownOmitsVehicleFieldsRatherThanSendingZeros() {
        String json = TelemetryPayload.build(1_700_000_000L, false, 0, 0f, 0,
                0f, 0f, false, false, false, 0f, 48.85, 2.35, null, null);
        assertFalse(json.contains("soc"));
        assertFalse(json.contains("speed"));
        assertFalse(json.contains("est_battery_range"));
        assertFalse(json.contains("power"));
        assertFalse(json.contains("is_parked"));
        // Location is independent of the car link and must still be sent.
        assertTrue(json.contains("\"lat\":48.85"));
        assertTrue(json.contains("\"lon\":2.35"));
    }

    @Test
    public void carUpEmitsVehicleFields() {
        String json = carOnly();
        assertTrue(json.contains("\"soc\":63"));
        assertTrue(json.contains("\"speed\":48"));          // rounded
        assertTrue(json.contains("\"est_battery_range\":210"));
        assertTrue(json.contains("\"ext_temp\":12"));
        assertTrue(json.contains("\"power\":-14.50"));      // 2 decimals, Locale.US
        assertTrue(json.contains("\"is_charging\":0"));
        assertTrue(json.contains("\"is_dcfc\":0"));
        assertTrue(json.contains("\"is_parked\":0"));
    }

    @Test
    public void powerUsesDotDecimalSeparatorRegardlessOfDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);   // comma locale
            assertTrue(carOnly().contains("\"power\":-14.50"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void implausibleCabinTempIsOmitted() {
        // 0.0 means the property is unsupported on this VHAL.
        String zero = TelemetryPayload.build(1L, true, 50, 0f, 0, 0f, 0f,
                false, false, true, 0f, null, null, null, null);
        assertFalse(zero.contains("cabin_temp"));

        String tooHot = TelemetryPayload.build(1L, true, 50, 0f, 0, 0f, 0f,
                false, false, true, 120f, null, null, null, null);
        assertFalse(tooHot.contains("cabin_temp"));
    }

    @Test
    public void plausibleCabinTempIsSent() {
        assertTrue(carOnly().contains("\"cabin_temp\":22"));   // 21.5 rounds to 22
    }

    @Test
    public void missingLocationOmitsAllLocationFields() {
        String json = carOnly();
        assertFalse(json.contains("lat"));
        assertFalse(json.contains("lon"));
        assertFalse(json.contains("elevation"));
        assertFalse(json.contains("heading"));
    }

    @Test
    public void elevationAndHeadingAreOptional() {
        String flat = TelemetryPayload.build(1L, false, 0, 0f, 0, 0f, 0f,
                false, false, false, 0f, 48.85, 2.35, null, null);
        assertFalse(flat.contains("elevation"));
        assertFalse(flat.contains("heading"));

        String full = TelemetryPayload.build(1L, false, 0, 0f, 0, 0f, 0f,
                false, false, false, 0f, 48.85, 2.35, 35.4, 271.6f);
        assertTrue(full.contains("\"elevation\":35"));
        assertTrue(full.contains("\"heading\":272"));
    }

    @Test
    public void dcfcAndChargingFlagsAreEmittedAsIntegers() {
        String json = TelemetryPayload.build(1L, true, 50, 0f, 0, 0f, 62f,
                true, true, true, 0f, null, null, null, null);
        assertTrue(json.contains("\"is_charging\":1"));
        assertTrue(json.contains("\"is_dcfc\":1"));
        assertTrue(json.contains("\"is_parked\":1"));
    }
}
