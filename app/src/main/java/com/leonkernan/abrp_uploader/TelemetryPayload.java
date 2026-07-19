package com.leonkernan.abrp_uploader;

import java.util.Locale;

/**
 * Builds the ABRP telemetry JSON payload.
 *
 * Kept free of Android types so it can be unit-tested on the JVM: the caller
 * pulls the values out of the car adapter and the Location, this class only
 * decides what ends up in the payload.
 */
final class TelemetryPayload {

    private TelemetryPayload() { }

    /**
     * Build the {@code tlm} JSON object.
     *
     * Always includes utc; only includes vehicle-derived fields if we actually
     * have a live car link (avoid blasting zeros). Location fields are included
     * only when the corresponding value is non-null.
     */
    static String build(long utc,
                        boolean carUp,
                        int soc,
                        float speedKmh,
                        int rangeKm,
                        float extTemp,
                        float powerKw,
                        boolean charging,
                        boolean dcfc,
                        boolean parked,
                        float cabinTemp,
                        Double lat,
                        Double lon,
                        Double elevation,
                        Float heading) {
        StringBuilder tlm = new StringBuilder("{\"utc\":").append(utc);
        if (carUp) {
            tlm.append(",\"soc\":").append(soc)
               .append(",\"speed\":").append(Math.round(speedKmh))
               .append(",\"est_battery_range\":").append(rangeKm)
               .append(",\"ext_temp\":").append(Math.round(extTemp))
               .append(",\"power\":").append(String.format(Locale.US, "%.2f", powerKw))
               .append(",\"is_charging\":").append(charging ? 1 : 0)
               .append(",\"is_dcfc\":").append(dcfc ? 1 : 0)
               .append(",\"is_parked\":").append(parked ? 1 : 0);
            // Only send cabin_temp if we got a plausible reading — 0.0 likely
            // means the property isn't supported on this VHAL.
            if (cabinTemp > -50f && cabinTemp < 80f && cabinTemp != 0f) {
                tlm.append(",\"cabin_temp\":").append(Math.round(cabinTemp));
            }
        }
        if (lat != null && lon != null) {
            tlm.append(",\"lat\":").append(lat.doubleValue());
            tlm.append(",\"lon\":").append(lon.doubleValue());
            if (elevation != null)
                tlm.append(",\"elevation\":").append(Math.round(elevation.doubleValue()));
            if (heading != null)
                tlm.append(",\"heading\":").append(Math.round(heading.floatValue()));
        }
        tlm.append("}");
        return tlm.toString();
    }
}
