package com.mg4.abrptelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * [T-913] Telemetry payload construction — pure JVM, no car and no Android needed.
 *
 * The rule under test: a property that could not be read is OMITTED. Emitting 0 instead
 * tells ABRP the car really is at 0 % / 0 km/h and corrupts the user's route plan.
 */
public class TelemetryPayloadTest {

    /** Everything readable, no location. */
    private String fullCar() {
        return TelemetryPayload.build(1_700_000_000L, 63, 48.4f, 210,
                12f, -14.5f, false, false, false, 21.5f,
                null, null, null, null);
    }

    /** Nothing readable at all — the car link is down. */
    private String noCar() {
        return TelemetryPayload.build(1_700_000_000L, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    public void utcIsAlwaysPresent() throws Exception {
        JSONObject json = new JSONObject(noCar());
        assertEquals(1, json.length());
        assertEquals(1_700_000_000L, json.getLong("utc"));
    }

    @Test
    public void unreadablePropertiesAreOmittedNotZeroed() {
        String json = noCar();
        for (String field : new String[]{
                "soc", "speed", "est_battery_range", "ext_temp", "power",
                "is_charging", "is_dcfc", "is_parked", "cabin_temp"}) {
            assertFalse("le champ " + field + " aurait dû être omis", json.contains(field));
        }
    }

    @Test
    public void aSingleUnreadablePropertyDoesNotSuppressTheOthers() throws Exception {
        // SOC throws (null) but speed reads fine: speed must still be sent.
        String raw = TelemetryPayload.build(1L, null, 48.4f, null, null, null,
                null, null, null, null, null, null, null, null);
        JSONObject json = new JSONObject(raw);
        assertFalse("soc illisible aurait dû être omis", json.has("soc"));
        assertEquals(48, json.getInt("speed"));
    }

    @Test
    public void aGenuineZeroIsStillSent() throws Exception {
        // 0 % battery is a real reading and must not be confused with "unreadable".
        JSONObject json = new JSONObject(TelemetryPayload.build(1L, 0, 0f, 0, null, null,
                null, null, null, null, null, null, null, null));
        assertEquals(0, json.getInt("soc"));
        assertEquals(0, json.getInt("speed"));
        assertEquals(0, json.getInt("est_battery_range"));
    }

    @Test
    public void readablePropertiesAreEmitted() throws Exception {
        JSONObject json = new JSONObject(fullCar());
        assertEquals(63, json.getInt("soc"));
        assertEquals(48, json.getInt("speed"));               // rounded
        assertEquals(210, json.getInt("est_battery_range"));
        assertEquals(12, json.getInt("ext_temp"));
        assertEquals(-14.5, json.getDouble("power"), 0.001);
        assertEquals(0, json.getInt("is_charging"));
        assertEquals(0, json.getInt("is_dcfc"));
        assertEquals(0, json.getInt("is_parked"));
    }

    @Test
    public void powerUsesDotDecimalSeparatorRegardlessOfDefaultLocale() throws Exception {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);   // comma locale
            assertTrue(fullCar().contains("-14.5"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    public void implausibleCabinTempIsOmitted() {
        // 0.0 means the property is unsupported on this VHAL.
        assertFalse(TelemetryPayload.build(1L, 50, null, null, null, null,
                null, null, true, 0f, null, null, null, null).contains("cabin_temp"));
        assertFalse(TelemetryPayload.build(1L, 50, null, null, null, null,
                null, null, true, 120f, null, null, null, null).contains("cabin_temp"));
    }

    @Test
    public void plausibleCabinTempIsSent() throws Exception {
        assertEquals(22, new JSONObject(fullCar()).getInt("cabin_temp"));  // 21.5 rounds up
    }

    @Test
    public void locationIsIndependentOfTheCarLink() throws Exception {
        // No car data at all, but a GPS fix: ABRP should still see the vehicle online.
        JSONObject json = new JSONObject(TelemetryPayload.build(1L, null, null, null, null,
                null, null, null, null, null, 48.85, 2.35, null, null));
        assertEquals(48.85, json.getDouble("lat"), 0.0001);
        assertEquals(2.35, json.getDouble("lon"), 0.0001);
        assertFalse(json.has("soc"));
    }

    @Test
    public void missingLocationOmitsAllLocationFields() {
        String json = fullCar();
        assertFalse(json.contains("lat"));
        assertFalse(json.contains("lon"));
        assertFalse(json.contains("elevation"));
        assertFalse(json.contains("heading"));
    }

    @Test
    public void elevationAndHeadingAreOptional() throws Exception {
        JSONObject flat = new JSONObject(TelemetryPayload.build(1L, null, null, null, null,
                null, null, null, null, null, 48.85, 2.35, null, null));
        assertFalse(flat.has("elevation"));
        assertFalse(flat.has("heading"));

        JSONObject full = new JSONObject(TelemetryPayload.build(1L, null, null, null, null,
                null, null, null, null, null, 48.85, 2.35, 35.4, 271.6f));
        assertEquals(35, full.getInt("elevation"));
        assertEquals(272, full.getInt("heading"));
    }

    @Test
    public void booleanFlagsAreEmittedAsIntegers() throws Exception {
        JSONObject json = new JSONObject(TelemetryPayload.build(1L, 50, null, null, null,
                62f, true, true, true, null, null, null, null, null));
        assertEquals(1, json.getInt("is_charging"));
        assertEquals(1, json.getInt("is_dcfc"));
        assertEquals(1, json.getInt("is_parked"));
    }

    @Test
    public void thePayloadIsValidJson() throws Exception {
        // The hand-built string could not guarantee this; JSONObject can.
        new JSONObject(fullCar());
        new JSONObject(noCar());
    }
}
