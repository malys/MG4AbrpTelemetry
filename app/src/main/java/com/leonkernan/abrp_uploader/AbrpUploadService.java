package com.leonkernan.abrp_uploader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that polls vehicle telemetry every 15 s and pushes it to ABRP.
 *
 * Reliability design:
 *  - Uses ScheduledExecutorService (not Handler chains) so exceptions inside one
 *    upload cycle cannot break the schedule. Even on a thrown RuntimeException
 *    the next tick still fires.
 *  - Uploads unconditionally: if the car adapter never connects we still emit
 *    GPS + timestamp so ABRP at least sees the vehicle online.
 *  - Car adapter (re-)connection is retried every 30 s by the scheduler itself
 *    if {@link CarPropertyAdapter#isConnected()} is false. This handles the
 *    case where the underlying android.car.Car createCar() / connect() call
 *    fails silently with no listener callback.
 *  - All IPC + HTTP happens on the scheduler thread, never the main thread.
 */
public class AbrpUploadService extends Service {

    public static final String ACTION_STOP = "com.leonkernan.abrp_uploader.STOP";

    private static final String TAG             = "AbrpUploadService";
    private static final String CHANNEL_ID      = "abrp_uploader";
    private static final int    NOTIF_ID        = 1;
    private static final long   UPLOAD_INTERVAL_SEC = 15;
    private static final long   CAR_RECONNECT_INTERVAL_SEC = 30;

    // Set from the main thread (LocationListener uses Main looper), read from
    // the scheduler thread inside doUpload. volatile is sufficient since Location
    // is effectively immutable for our purposes.
    private volatile Location lastLocation;

    // Replaced on the main thread by connectCarAdapter, read from the scheduler thread.
    private volatile CarPropertyAdapter carAdapter;
    private LocationManager      locationManager;
    private ScheduledExecutorService scheduler;
    private SharedPreferences    prefs;
    /** Credentials only — encrypted at rest, see {@link SecurePrefs}. */
    private SharedPreferences    securePrefs;
    private Handler              mainHandler;

    private volatile long lastSuccessfulUploadMs = 0L;
    private volatile long lastCarConnectAttemptMs = 0L;

    // ---------- Lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs       = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        securePrefs = SecurePrefs.get(this);
        mainHandler = new Handler(Looper.getMainLooper());
        scheduler   = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "abrp-upload");
            t.setDaemon(false);
            return t;
        });

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Starting…"));
        prefs.edit().putBoolean("service_running", true).apply();

        connectCarAdapter();
        requestLocationUpdates();

        // Fire the first upload after a short warm-up, then every UPLOAD_INTERVAL_SEC.
        // scheduleWithFixedDelay ensures we always get at least UPLOAD_INTERVAL_SEC
        // between cycles even if a previous upload was slow.
        scheduler.scheduleWithFixedDelay(
                this::safeUploadCycle,
                45, UPLOAD_INTERVAL_SEC, TimeUnit.SECONDS);

        Log.i(TAG, "Service started, upload scheduler armed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("service_enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service stopping");
        if (scheduler != null) scheduler.shutdownNow();
        if (carAdapter != null) carAdapter.disconnect();
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        prefs.edit().putBoolean("service_running", false).apply();
        super.onDestroy();
    }

    // ---------- Car adapter ----------

    private void connectCarAdapter() {
        lastCarConnectAttemptMs = System.currentTimeMillis();
        // Must run on main thread because ServiceConnection callbacks need a Looper.
        mainHandler.post(() -> {
            if (carAdapter != null) {
                try { carAdapter.disconnect(); } catch (Exception ignored) {}
            }
            carAdapter = new CarPropertyAdapter(new CarPropertyAdapter.Listener() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "CarPropertyAdapter connected");
                }
                @Override
                public void onDisconnected() {
                    Log.w(TAG, "CarPropertyAdapter disconnected — scheduler will retry");
                    // Reconnect is handled by the scheduler watchdog.
                }
            });
            try {
                carAdapter.connect(AbrpUploadService.this);
            } catch (Throwable t) {
                Log.e(TAG, "carAdapter.connect threw", t);
            }
        });
    }

    // ---------- Upload cycle (runs on scheduler thread) ----------

    private void safeUploadCycle() {
        try {
            // Self-watchdog: if the car adapter looks dead and we haven't tried
            // reconnecting recently, kick a reconnect.
            if ((carAdapter == null || !carAdapter.isConnected())
                    && System.currentTimeMillis() - lastCarConnectAttemptMs
                       > CAR_RECONNECT_INTERVAL_SEC * 1000) {
                Log.w(TAG, "Car adapter not connected, attempting reconnect");
                connectCarAdapter();
            }
            doUpload();
        } catch (Throwable t) {
            // Absolutely never let an exception escape — would not break the
            // schedule with scheduleWithFixedDelay's executor, but we want a
            // clean log line anyway.
            Log.e(TAG, "upload cycle threw", t);
        }
    }

    private void doUpload() {
        String token  = securePrefs.getString(SecurePrefs.KEY_TOKEN,   "").trim();
        String apiKey = securePrefs.getString(SecurePrefs.KEY_API_KEY, "").trim();

        if (token.isEmpty()) {
            updateNotification("No ABRP token — open app to configure");
            return;
        }
        if (apiKey.isEmpty()) {
            updateNotification("No API key — open app to configure");
            return;
        }

        // Read whatever the car will give us. Every getter returns null when the read
        // fails, and a null field is omitted from the payload — never sent as 0.
        boolean carUp = carAdapter != null && carAdapter.isConnected();

        Float speedKmh = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_VEHICLE_SPEED,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        Float socRaw = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_BATTERY_PCT,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;
        Integer soc = socRaw == null ? null : Math.round(socRaw);

        Integer rangeKm = carUp ? carAdapter.getIntProperty(
                CarPropertyAdapter.PROP_EV_RANGE_KM,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        Float extTemp = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_OUTSIDE_TEMP,
                CarPropertyAdapter.PROP_AREA_HVAC) : null;

        // Charge rate comes in mW (signed: +ve charging, -ve driving). ABRP wants kW.
        Float chargeRate = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_INSTANTANEOUS_CHARGE_RATE,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;
        Float powerKw = chargeRate == null ? null : chargeRate / 1_000_000f;

        Boolean charging = carUp ? carAdapter.getBooleanProperty(
                CarPropertyAdapter.PROP_EV_CHARGE_PORT_CONNECTED,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        // Free derived signals
        Integer gear = carUp ? carAdapter.getIntProperty(
                CarPropertyAdapter.PROP_GEAR_SELECTION,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;
        // Unknown gear means unknown parked state — not "not parked".
        Boolean parked = gear == null ? null : (gear == CarPropertyAdapter.GEAR_PARK);

        // DCFC heuristic: charging at AC speeds (3-22 kW) is type-2; above ~25 kW
        // it can only be DC fast. Undecidable if either input is missing.
        Boolean dcfc = (charging == null || powerKw == null)
                ? null : (charging && powerKw > 25f);

        // Cabin temperature — try the standard HVAC property, may fail on this VHAL.
        Float cabinTemp = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_CABIN_TEMP,
                CarPropertyAdapter.PROP_AREA_HVAC) : null;

        long utc = System.currentTimeMillis() / 1000;
        Location loc = lastLocation;

        String tlm = TelemetryPayload.build(
                utc, soc, speedKmh, rangeKm, extTemp, powerKw,
                charging, dcfc, parked, cabinTemp,
                loc != null ? loc.getLatitude()  : null,
                loc != null ? loc.getLongitude() : null,
                (loc != null && loc.hasAltitude()) ? loc.getAltitude() : null,
                (loc != null && loc.hasBearing()) ? loc.getBearing()  : null);

        sendToAbrp(apiKey, token, tlm, soc, speedKmh, carUp);
    }

    private void sendToAbrp(String apiKey, String token, String tlmJson,
                            Integer soc, Float speedKmh, boolean carUp) {
        try {
            AbrpApi.Response response = AbrpApi.send(apiKey, token, tlmJson);
            int code = response.code;

            // The response body can echo request details — debug builds only.
            if (BuildConfig.DEBUG) Log.d(TAG, "ABRP [" + code + "]: " + response.body);

            if (code == 200) {
                lastSuccessfulUploadMs = System.currentTimeMillis();
                String time = android.text.format.DateFormat
                        .format("HH:mm:ss", lastSuccessfulUploadMs).toString();
                prefs.edit()
                        .putString("last_upload_time",   time)
                        .putString("last_upload_status", "OK")
                        .apply();
                // Only claim a value in the notification if we actually read one.
                String detail = (soc != null && speedKmh != null)
                        ? ("SOC " + soc + "% · " + Math.round(speedKmh) + " km/h · " + time)
                        : (carUp ? ("Car data partial · " + time) : ("GPS only · " + time));
                updateNotification(detail);
            } else {
                prefs.edit().putString("last_upload_status", "HTTP " + code).apply();
                updateNotification("Upload error: HTTP " + code);
            }
        } catch (java.net.UnknownHostException e) {
            prefs.edit().putString("last_upload_status", "No internet").apply();
            updateNotification("Offline — will retry");
        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            prefs.edit().putString("last_upload_status",
                    e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
        }
    }

    // ---------- Location ----------

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { lastLocation = location; }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private void requestLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 10_000, 0f,
                        locationListener, Looper.getMainLooper());
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) lastLocation = last;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Location unavailable: " + e.getMessage());
        }
    }

    // ---------- Notification ----------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String status) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AbrpUploadService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(status)
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_delete, getString(R.string.notif_action_stop), stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(status));
    }
}
