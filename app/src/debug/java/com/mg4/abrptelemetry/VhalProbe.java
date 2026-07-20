package com.mg4.abrptelemetry;

import android.util.Log;

/**
 * VHAL discovery helpers — DEBUG BUILDS ONLY.
 *
 * These enumerate and log the entire vehicle abstraction layer. That is exactly the
 * surface a release APK on a customer vehicle should not carry, so the class lives in
 * src/debug and simply does not exist in a release build. It was previously part of
 * CarPropertyAdapter and shipped to production.
 *
 * Neither method is called by the service; they are attached from a debug session when
 * identifying which vendor property ID maps to a given sensor.
 */
final class VhalProbe {

    private static final String TAG = "VhalProbe";

    private VhalProbe() { }

    /**
     * Reads every vendor Float property visible in the car_service dump and logs non-zero values.
     * Use this to identify which vendor property ID maps to a given sensor.
     */
    static void probeVendorFloatProperties(CarPropertyAdapter adapter) {
        // Full Float-type property ID list from `dumpsys car_service` on this vehicle.
        int[] globalIds = {
            // Standard AAOS
            291504647, 291504901, 291518465, 291518466, 291518467, 291518468,
            // Vendor 0x2160xxxx global
            559945057, 559945059, 559945060, 559945125, 559945126,
            559971102, 559971105,
            559980820, 559980821, 559980822, 559980824, 559980825, 559980826, 559980827,
            559980833, 559980834, 559980835, 559980837, 559980838, 559980839, 559980840,
            559980844, 559980845, 559980846, 559980847, 559980848,
            559980850, 559980851, 559980852, 559980853, 559980854,
            559980857, 559980858, 559980859, 559980860, 559980861,
            559980864, 559980865, 559980866, 559980867, 559980868,
            559980872, 559980873, 559980875, 559980876, 559980877, 559980878,
            559980880, 559980881, 559980882, 559980883, 559980891,
            559980933, 559980951, 559980952, 559980953, 559980954, 559980955,
            559980961, 559980962, 559980963, 559980964, 559980968,
            559980987, 559980988, 559980989, 559980990, 559980991, 559980992, 559980993, 559980994,
            559981004, 559981005, 559981006, 559981007,
            559987029,
            559990304, 559990308,
            560002052, 560002053, 560002054, 560002055, 560002058,
            560002075, 560002077, 560002081,
            560002108, 560002109, 560002114,
        };
        // HVAC / climate properties use zone area ID 117
        int[] hvacIds = {
            358622475, 358622476, 358622481, 358622494, 358622495,
            358622496, 358622497, 358622506, 358622518,
        };

        Log.d(TAG, "probeVendorFloatProperties: scanning " + (globalIds.length + hvacIds.length) + " IDs");
        for (int id : globalIds) {
            Float val = adapter.getFloatProperty(id, 0);
            if (val != null && val != 0f) Log.i(TAG, String.format("  GLOBAL 0x%08X = %.4f", id, val));
        }
        for (int id : hvacIds) {
            Float val = adapter.getFloatProperty(id, 117);
            if (val != null && val != 0f) Log.i(TAG, String.format("  HVAC[117] 0x%08X = %.4f", id, val));
            Float val0 = adapter.getFloatProperty(id, 0);
            if (val0 != null && val0 != 0f) Log.i(TAG, String.format("  HVAC[0] 0x%08X = %.4f", id, val0));
        }
        Log.d(TAG, "probeVendorFloatProperties: done");
    }

    /**
     * Enumerates every supported property and logs its current value. Used for
     * discovery: match logged values against the dashboard to identify which
     * vendor property ID maps to a given sensor (battery %, range, etc).
     */
    static void logAllProperties(CarPropertyAdapter adapter) {
        if (adapter.cpm == null) { Log.w(TAG, "logAllProperties: not connected"); return; }
        Log.d(TAG, "logAllProperties: starting");
        try {
            java.lang.reflect.Method listMethod = adapter.cpmClass.getMethod("getPropertyList");
            java.util.List<?> configs = (java.util.List<?>) listMethod.invoke(adapter.cpm);
            Log.d(TAG, "getPropertyList returned: " + (configs == null ? "null" : configs.size() + " entries"));
            if (configs == null || configs.isEmpty()) return;
            Class<?> configClass = Class.forName("android.car.hardware.CarPropertyConfig", true, adapter.cpmClass.getClassLoader());
            java.lang.reflect.Method getPropId   = configClass.getMethod("getPropertyId");
            java.lang.reflect.Method getPropType = configClass.getMethod("getPropertyType");
            java.lang.reflect.Method getAreaIds  = configClass.getMethod("getAreaIds");
            for (Object cfg : configs) {
                int      propId = (int)      getPropId.invoke(cfg);
                Class<?> type   = (Class<?>) getPropType.invoke(cfg);
                int[]    areas  = (int[])    getAreaIds.invoke(cfg);
                int      area   = (areas != null && areas.length > 0) ? areas[0] : 0;
                try {
                    if (type == Float.class) {
                        Log.i(TAG, String.format("FLOAT  0x%08X area=%d = %s", propId, area,
                                adapter.getFloatProperty(propId, area)));
                    } else if (type == Integer.class) {
                        Log.i(TAG, String.format("INT    0x%08X area=%d = %s", propId, area,
                                adapter.getIntProperty(propId, area)));
                    } else if (type == Boolean.class) {
                        Log.i(TAG, String.format("BOOL   0x%08X area=%d = %s", propId, area,
                                adapter.getBooleanProperty(propId, area)));
                    } else {
                        Log.i(TAG, String.format("OTHER  0x%08X area=%d type=%s", propId, area, type));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, String.format("read 0x%08X area=%d failed: %s",
                            propId, area, t.getClass().getSimpleName()));
                }
            }
            Log.d(TAG, "logAllProperties: done");
        } catch (Exception e) {
            Log.e(TAG, "logAllProperties failed", e);
        }
    }

}
