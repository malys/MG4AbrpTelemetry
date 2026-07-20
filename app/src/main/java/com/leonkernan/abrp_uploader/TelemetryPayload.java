package com.leonkernan.abrp_uploader;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the ABRP telemetry JSON payload.
 *
 * Kept free of Android types so it can be unit-tested on the JVM: the caller
 * pulls the values out of the car adapter and the Location, this class only
 * decides what ends up in the payload.
 *
 * Every vehicle field is nullable and a null field is OMITTED. A property that
 * cannot be read is not a zero: sending soc:0 because a getter threw tells ABRP
 * the battery is empty and wrecks the user's route plan.
 */
final class TelemetryPayload {

    private TelemetryPayload() { }

    /** Build the {@code tlm} JSON object. Only utc is always present. */
    static String build(long utc,
                        Integer soc,
                        Float speedKmh,
                        Integer rangeKm,
                        Float extTemp,
                        Float powerKw,
                        Boolean charging,
                        Boolean dcfc,
                        Boolean parked,
                        Float cabinTemp,
                        Double lat,
                        Double lon,
                        Double elevation,
                        Float heading) {
        JSONObject tlm = new JSONObject();
        try {
            tlm.put("utc", utc);

            putIfPresent(tlm, "soc", soc);
            putIfPresent(tlm, "speed", speedKmh == null ? null : Math.round(speedKmh));
            putIfPresent(tlm, "est_battery_range", rangeKm);
            putIfPresent(tlm, "ext_temp", extTemp == null ? null : Math.round(extTemp));
            if (powerKw != null) tlm.put("power", round2(powerKw));
            putIfPresent(tlm, "is_charging", boolToInt(charging));
            putIfPresent(tlm, "is_dcfc", boolToInt(dcfc));
            putIfPresent(tlm, "is_parked", boolToInt(parked));

            // Only send cabin_temp if we got a plausible reading — 0.0 likely
            // means the property isn't supported on this VHAL.
            if (cabinTemp != null && cabinTemp > -50f && cabinTemp < 80f && cabinTemp != 0f) {
                tlm.put("cabin_temp", Math.round(cabinTemp));
            }

            if (lat != null && lon != null) {
                tlm.put("lat", lat.doubleValue());
                tlm.put("lon", lon.doubleValue());
                putIfPresent(tlm, "elevation", elevation == null ? null : Math.round(elevation));
                putIfPresent(tlm, "heading", heading == null ? null : Math.round(heading));
            }
        } catch (JSONException e) {
            // JSONObject.put only throws on NaN/Infinity values, which the guards above
            // already exclude. Returning just the timestamp keeps the upload alive.
            return "{\"utc\":" + utc + "}";
        }
        return tlm.toString();
    }

    private static void putIfPresent(JSONObject json, String key, Object value)
            throws JSONException {
        if (value != null) json.put(key, value);
    }

    private static Integer boolToInt(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }

    /** ABRP expects kW with 2 decimals; avoids a locale-dependent String.format. */
    private static double round2(float value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
