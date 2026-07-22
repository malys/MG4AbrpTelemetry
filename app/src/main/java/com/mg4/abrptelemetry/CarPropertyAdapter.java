package com.mg4.abrptelemetry;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Connects to Android Automotive's CarPropertyManager via reflection.
 *
 * Targets AAOS 9 (API 28) where android.car is a platform library not in the
 * compile-time SDK — direct imports are unavailable, so all access is via reflection.
 *
 * Usage:
 * <pre>
 *     CarPropertyAdapter adapter = new CarPropertyAdapter(listener);
 *     adapter.connect(context);
 *
 *     // After onConnected():
 *     float speed = adapter.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
 * </pre>
 *
 * Property constants confirmed on this vehicle:
 *   OUTSIDE_TEMP           = 0x15602511, area 117  — ambient °C (matches HMI display)
 *   PERF_VEHICLE_SPEED     = 0x11600207, area 0    — speed m/s (standard AAOS)
 */
public class CarPropertyAdapter {

    private static final String TAG = "CarPropertyAdapter";

    public interface Listener {
        void onConnected();
        void onDisconnected();
    }

    // Confirmed property IDs for this vehicle (AAOS 9, SAIC platform).
    //
    // Provenance: reverse-engineered from the MG4 (eh32) "R69" distribution, firmware
    // build SWI68-29958-1300R69 — the same ROM the apks/ reference dump was decompiled
    // from. The vendor-space IDs below (0x216xxxxx / 0x214xxxxx) are OEM-specific and only
    // known-valid for this firmware generation (SWI68). MG4 ships several generations
    // (SWI68/69/131/132/133/165) and the sibling MG4Control project branches its vehicle
    // calls per generation; this app does not. On a different firmware these vendor reads
    // may return nothing — which is handled safely (the getters return null and the field
    // is omitted, see T-913), but SOC and range would then be silently absent. Re-confirm
    // the vendor IDs on any other generation with VhalProbe (debug builds).
    public static final int PROP_OUTSIDE_TEMP   = 0x15602511; // float °C, area 117
    public static final int PROP_VEHICLE_SPEED  = 0x11600207; // float km/h, area 0
    public static final int PROP_GEAR_SELECTION = 0x11400400; // int (VehicleGear), area 0
    // Vendor EV cluster — matched against the SAIC stack's BatteryPercent / CurrentRange:
    public static final int PROP_EV_BATTERY_PCT = 0x2160F404; // float % (e.g. 90.5)
    public static final int PROP_EV_RANGE_KM    = 0x2140F41C; // int km
    // Standard AAOS EV properties (may or may not be implemented by this VHAL —
    // if not, look for vendor equivalents via VhalProbe (debug builds only):
    //  - charge rate is signed mW: positive while charging, negative while driving
    //  - charge port connected is bool: true when cable plugged in
    public static final int PROP_EV_INSTANTANEOUS_CHARGE_RATE = 0x1160030C; // float mW, area 0
    public static final int PROP_EV_CHARGE_PORT_CONNECTED     = 0x1120030A; // bool,    area 0
    // Standard AAOS HVAC: cabin temperature (measured). Zoned — read from HVAC area.
    public static final int PROP_CABIN_TEMP     = 0x15600903; // float °C, area 117
    public static final int PROP_AREA_GLOBAL    = 0;
    public static final int PROP_AREA_HVAC      = 117;

    // VehicleGear bitfield values (standard AAOS) — these are flags, not ordinals.
    public static final int GEAR_UNKNOWN  = 0x0000;
    public static final int GEAR_NEUTRAL  = 0x0001;
    public static final int GEAR_REVERSE  = 0x0002;
    public static final int GEAR_PARK     = 0x0004;
    public static final int GEAR_DRIVE    = 0x0008;

    private final Listener listener;
    private Context        appContext;
    // Written on the binder/main thread, read from the abrp-upload scheduler thread.
    // Without volatile the scheduler can observe a stale null or a half-connected adapter.
    private volatile Object car;      // android.car.Car
    volatile Object cpm;              // android.car.hardware.property.CarPropertyManager (package-private: VhalProbe, debug builds only)
    private Class<?>       carClass;
    Class<?>               cpmClass;

    // Reflected getters resolved once at connect time. getMethod() on every read meant
    // 8 lookups every 15 s, each allocating a Method and its parameter array.
    private volatile Method getIntMethod;
    private volatile Method getFloatMethod;
    private volatile Method getBooleanMethod;

    /**
     * Incremented on every disconnect. A polling runnable captures the value it started
     * with and stops as soon as it no longer matches, so a reconnect cannot leave an
     * orphan polling loop running alongside the new one.
     */
    private volatile int connectionGeneration = 0;

    public CarPropertyAdapter(Listener listener) {
        this.listener = listener;
    }

    public void connect(Context context) {
        appContext = context.getApplicationContext();

        try {
            carClass = Class.forName("android.car.Car");
            cpmClass = Class.forName("android.car.hardware.property.CarPropertyManager");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "android.car not available — not running on AAOS", e);
            return;
        }

        ServiceConnection sc = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    String svcName = (String) carClass.getField("PROPERTY_SERVICE").get(null);
                    Method getManager = carClass.getMethod("getCarManager", String.class);
                    Object manager = getManager.invoke(car, svcName);
                    if (manager != null) {
                        getIntMethod     = cpmClass.getMethod("getIntProperty", int.class, int.class);
                        getFloatMethod   = cpmClass.getMethod("getFloatProperty", int.class, int.class);
                        getBooleanMethod = cpmClass.getMethod("getBooleanProperty", int.class, int.class);
                        // Publish last: isConnected() must never be true before the
                        // cached methods are in place.
                        cpm = manager;
                        Log.i(TAG, "CarPropertyManager ready");
                        listener.onConnected();
                    } else {
                        Log.e(TAG, "getCarManager returned null for: " + svcName);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get CarPropertyManager", e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                connectionGeneration++;
                cpm = null;
                car = null;
                Log.w(TAG, "Car service disconnected");
                listener.onDisconnected();
            }
        };

        try {
            Method createCar = carClass.getMethod("createCar", Context.class, ServiceConnection.class);
            car = createCar.invoke(null, appContext, sc);
            carClass.getMethod("connect").invoke(car);
            Log.d(TAG, "Connecting to Car service...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create/connect Car", e);
        }
    }

    public void disconnect() {
        // Bump first: any in-flight polling runnable must stop even if the calls below throw.
        connectionGeneration++;
        cpm = null;
        if (car == null) return;
        try {
            carClass.getMethod("disconnect").invoke(car);
        } catch (Exception e) {
            Log.e(TAG, "disconnect error", e);
        }
        car = null;
    }

    public boolean isConnected() {
        return cpm != null;
    }

    public interface PropertyCallback {
        void onValue(int propId, int areaId, Object value);
    }

    /**
     * Polls a float property on the given handler at the specified interval.
     * Stops automatically when disconnect() is called.
     * This is the preferred mechanism on AAOS 9 where CarPropertyValue is not
     * accessible via any standard classloader path, making Proxy-based listeners fail.
     */
    public void startFloatPolling(int propId, int areaId, long intervalMs,
                                  android.os.Handler handler, PropertyCallback cb) {
        if (cpm == null) { Log.w(TAG, "startFloatPolling: not connected"); return; }
        final int generation = connectionGeneration;
        Runnable[] r = {null};
        r[0] = () -> {
            // Checking cpm alone was not enough: a reconnect makes it non-null again and
            // the orphan loop resumed forever next to the new one.
            if (cpm == null || connectionGeneration != generation) return;
            Float val = getFloatProperty(propId, areaId);
            if (val != null) cb.onValue(propId, areaId, val);
            handler.postDelayed(r[0], intervalMs);
        };
        handler.post(r[0]);
        Log.d(TAG, String.format("started polling 0x%08X every %dms", propId, intervalMs));
    }

    /**
     * Subscribes to updates for any car property (float, int, bool — type-agnostic).
     * The callback receives the raw {@link Object} value; cast to the expected type.
     *
     * AAOS 9 quirks handled here:
     *  - Method is named registerListener (not registerCallback as in later versions).
     *  - The event callback interface and CarPropertyValue class are discovered from
     *    the register method's signature — on this SAIC build CarPropertyValue lives
     *    at android.car.hardware.CarPropertyValue (NOT android.car.hardware.property.*).
     *  - The Proxy is defined with this class's loader so the framework can verify it.
     *
     * @param rateHz  for CONTINUOUS properties, the requested rate in Hz.
     *                For ON_CHANGE properties (e.g. gear), this is ignored — pass 0f.
     */
    public void registerPropertyCallback(int propId, int areaId, float rateHz, PropertyCallback cb) {
        if (cpm == null) { Log.w(TAG, "registerPropertyCallback: not connected"); return; }
        try {
            // Find a 3-arg register* method on CarPropertyManager: (Listener, propId, rateHz).
            // Match by exact name: getMethods() has no defined order, so "the first
            // 3-arg register* method" could pick a different overload between runs.
            java.lang.reflect.Method registerMethod = null;
            for (java.lang.reflect.Method m : cpmClass.getMethods()) {
                String n = m.getName();
                if (("registerListener".equals(n) || "registerCallback".equals(n))
                        && m.getParameterCount() == 3) {
                    Log.d(TAG, "register candidate: " + m.toGenericString());
                    registerMethod = m;
                    break;
                }
            }
            if (registerMethod == null) {
                Log.e(TAG, "no register method found on CarPropertyManager");
                return;
            }

            Class<?> cbInterface = registerMethod.getParameterTypes()[0];
            Log.d(TAG, "callback interface: " + cbInterface.getName());

            // Discover CarPropertyValue directly from the interface's onChangeEvent
            // parameter type. This sidesteps hardcoding a package path that differs
            // between AAOS builds.
            Class<?> cpvClass = null;
            for (java.lang.reflect.Method m : cbInterface.getMethods()) {
                Log.d(TAG, "  interface method: " + m.toGenericString());
                if ("onChangeEvent".equals(m.getName()) && m.getParameterCount() == 1) {
                    cpvClass = m.getParameterTypes()[0];
                }
            }
            if (cpvClass == null) {
                Log.e(TAG, "no onChangeEvent(CarPropertyValue) method on " + cbInterface.getName());
                return;
            }
            Log.d(TAG, "CarPropertyValue resolved to: " + cpvClass.getName());

            java.lang.reflect.Method getPropId = cpvClass.getMethod("getPropertyId");
            java.lang.reflect.Method getAreaId = cpvClass.getMethod("getAreaId");
            java.lang.reflect.Method getValue  = cpvClass.getMethod("getValue");

            // Use the app classloader (delegates to boot for android.car.* classes)
            // so the generated Proxy class can actually be defined.
            ClassLoader proxyLoader = CarPropertyAdapter.class.getClassLoader();

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    proxyLoader,
                    new Class[]{cbInterface},
                    (proxyObj, method, args) -> {
                        String mname = method.getName();
                        if ("onChangeEvent".equals(mname) && args != null && args.length == 1) {
                            int pId = (int) getPropId.invoke(args[0]);
                            int aId = (int) getAreaId.invoke(args[0]);
                            Object val = getValue.invoke(args[0]);
                            cb.onValue(pId, aId, val);
                        } else if ("onErrorEvent".equals(mname)) {
                            Log.e(TAG, "onErrorEvent propId=" + args[0] + " area=" + args[1]);
                        } else if ("equals".equals(mname)) {
                            return proxyObj == args[0];
                        } else if ("hashCode".equals(mname)) {
                            return System.identityHashCode(proxyObj);
                        } else if ("toString".equals(mname)) {
                            return "CarPropertyProxy@" + Integer.toHexString(System.identityHashCode(proxyObj));
                        }
                        return null;
                    });

            registerMethod.invoke(cpm, proxy, propId, rateHz);
            Log.i(TAG, String.format("registered callback for 0x%08X area=%d rate=%.1fHz",
                    propId, areaId, rateHz));
        } catch (Exception e) {
            Log.e(TAG, "registerPropertyCallback failed", e);
        }
    }

    // Getters return null when the property cannot be read. They used to return 0,
    // which is indistinguishable from a genuine zero: one SecurityException on the SOC
    // property was enough to tell ABRP the battery was empty, every 15 seconds.
    // Callers must omit a null field from the payload rather than substituting a value.

    /** Area 0 covers global/non-zoned properties. Null when unreadable. */
    public Integer getIntProperty(int propId, int areaId) {
        Object manager = cpm;
        Method method = getIntMethod;
        if (manager == null || method == null) return null;
        try {
            return (Integer) method.invoke(manager, propId, areaId);
        } catch (Exception e) {
            Log.e(TAG, "getIntProperty(" + Integer.toHexString(propId) + ") failed", e);
            return null;
        }
    }

    /** Null when unreadable. */
    public Float getFloatProperty(int propId, int areaId) {
        Object manager = cpm;
        Method method = getFloatMethod;
        if (manager == null || method == null) return null;
        try {
            return (Float) method.invoke(manager, propId, areaId);
        } catch (Exception e) {
            Log.e(TAG, "getFloatProperty(" + Integer.toHexString(propId) + ") failed", e);
            return null;
        }
    }

    /** Null when unreadable. */
    public Boolean getBooleanProperty(int propId, int areaId) {
        Object manager = cpm;
        Method method = getBooleanMethod;
        if (manager == null || method == null) return null;
        try {
            return (Boolean) method.invoke(manager, propId, areaId);
        } catch (Exception e) {
            Log.e(TAG, "getBooleanProperty(" + Integer.toHexString(propId) + ") failed", e);
            return null;
        }
    }
}
