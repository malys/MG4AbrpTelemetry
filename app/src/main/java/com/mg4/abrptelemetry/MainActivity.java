package com.mg4.abrptelemetry;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;


public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST = 100;

    private static final int COLOR_OK      = 0xFF4CAF50; // green
    private static final int COLOR_ERROR   = 0xFFF44336; // red
    private static final int COLOR_PENDING = 0xFF9E9E9E; // grey

    private TextInputLayout   apiKeyLayout;
    private TextInputEditText apiKeyInput;
    private TextInputLayout   tokenLayout;
    private TextInputEditText tokenInput;
    private SwitchMaterial    serviceSwitch;
    private TextView          statusText;
    private Button            testButton;
    private View              connectionStatusRow;
    private View              connectionIndicator;
    private TextView          connectionStatusText;
    private Spinner intervalSpinner;
    private SwitchMaterial boostSwitch;
    private TextInputEditText lowSocInput;
    private TextInputLayout lowSocLayout;
    private TextView callLogText;

    /** Refreshes state + call log while the screen is visible. */
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable uiRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshCallLog();
            uiHandler.postDelayed(this, 2_000L);
        }
    };

    private SharedPreferences prefs;
    /** Credentials only — encrypted at rest, see {@link SecurePrefs}. */
    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        securePrefs = SecurePrefs.get(this);

        apiKeyLayout        = findViewById(R.id.api_key_layout);
        apiKeyInput         = findViewById(R.id.api_key_input);
        tokenLayout         = findViewById(R.id.token_layout);
        tokenInput          = findViewById(R.id.token_input);
        serviceSwitch       = findViewById(R.id.service_switch);
        statusText          = findViewById(R.id.status_text);
        testButton          = findViewById(R.id.test_button);
        connectionStatusRow = findViewById(R.id.connection_status_row);
        connectionIndicator = findViewById(R.id.connection_indicator);
        connectionStatusText = findViewById(R.id.connection_status_text);
        intervalSpinner = findViewById(R.id.interval_spinner);
        boostSwitch = findViewById(R.id.boost_switch);
        lowSocInput = findViewById(R.id.low_soc_input);
        lowSocLayout = findViewById(R.id.low_soc_layout);
        callLogText = findViewById(R.id.call_log_text);

        bindCadenceControls();

        // Unstable builds check for a newer pre-release; the stable flavor's UpdateHook is
        // a no-op and does not even contain the updater.
        UpdateHook.checkInBackground(this);

        apiKeyInput.setText(securePrefs.getString(SecurePrefs.KEY_API_KEY, ""));
        tokenInput.setText(securePrefs.getString(SecurePrefs.KEY_TOKEN, ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));

        // If the user wants the service running, kick it on every activity launch.
        // startForegroundService is idempotent — if the service is already up this
        // is a no-op aside from delivering a new intent. We don't trust
        // service_running as a sole indicator because it can be stale-true if a
        // previous process was force-killed (e.g. by `adb install -r`) without
        // onDestroy running.
        boolean enabled = prefs.getBoolean("service_enabled", false);
        boolean haveCreds = !securePrefs.getString(SecurePrefs.KEY_TOKEN, "").trim().isEmpty()
                         && !securePrefs.getString(SecurePrefs.KEY_API_KEY, "").trim().isEmpty();
        if (enabled && haveCreds) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
            requestLocationPermissionIfNeeded();
        }

        findViewById(R.id.save_button).setOnClickListener(v -> saveCredentials());
        testButton.setOnClickListener(v -> testConnection());

        serviceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                String apiKey = textOf(apiKeyInput);
                String token  = textOf(tokenInput);
                if (apiKey.isEmpty() || token.isEmpty()) {
                    serviceSwitch.setChecked(false);
                    if (apiKey.isEmpty()) apiKeyLayout.setError(getString(R.string.api_key_required));
                    if (token.isEmpty())  tokenLayout.setError(getString(R.string.token_required));
                    return;
                }
                apiKeyLayout.setError(null);
                tokenLayout.setError(null);
                prefs.edit().putBoolean("service_enabled", true).apply();
                startForegroundService(new Intent(this, AbrpUploadService.class));
                requestLocationPermissionIfNeeded();
            } else {
                prefs.edit().putBoolean("service_enabled", false).apply();
                stopService(new Intent(this, AbrpUploadService.class));
            }
            refreshStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        apiKeyInput.setText(securePrefs.getString(SecurePrefs.KEY_API_KEY, ""));
        tokenInput.setText(securePrefs.getString(SecurePrefs.KEY_TOKEN, ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));
        // Poll state and log only while the screen is up; onPause cancels it.
        uiHandler.post(uiRefresh);
    }

    // ---------- Credentials ----------

    private void saveCredentials() {
        String apiKey = textOf(apiKeyInput);
        String token  = textOf(tokenInput);
        boolean valid = true;

        if (apiKey.isEmpty()) {
            apiKeyLayout.setError(getString(R.string.api_key_required));
            valid = false;
        } else {
            apiKeyLayout.setError(null);
        }
        if (token.isEmpty()) {
            tokenLayout.setError(getString(R.string.token_required));
            valid = false;
        } else {
            tokenLayout.setError(null);
        }
        if (!valid) return;

        securePrefs.edit()
                .putString(SecurePrefs.KEY_API_KEY, apiKey)
                .putString(SecurePrefs.KEY_TOKEN,   token)
                .apply();

        if (serviceSwitch.isChecked()) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }
        refreshStatus();
    }

    // ---------- Connection test ----------

    private void testConnection() {
        String apiKey = textOf(apiKeyInput);
        String token  = textOf(tokenInput);

        if (apiKey.isEmpty() || token.isEmpty()) {
            if (apiKey.isEmpty()) apiKeyLayout.setError(getString(R.string.api_key_required));
            if (token.isEmpty())  tokenLayout.setError(getString(R.string.token_required));
            return;
        }

        setConnectionStatus(COLOR_PENDING, getString(R.string.conn_testing));
        testButton.setEnabled(false);

        new Thread(() -> {
            String result = pingAbrp(apiKey, token);
            runOnUiThread(() -> {
                testButton.setEnabled(true);
                if (result == null) {
                    setConnectionStatus(COLOR_OK, getString(R.string.conn_ok));
                } else {
                    setConnectionStatus(COLOR_ERROR, result);
                }
            });
        }).start();
    }

    /**
     * Sends a minimal test request to the ABRP API.
     * Returns null on success, or a short error string on failure.
     */
    private String pingAbrp(String apiKey, String token) {
        try {
            // Read-only check. The previous version POSTed {"utc":...,"soc":0} to the LIVE
            // /tlm/send endpoint, i.e. told ABRP the car was at 0% every time the user
            // pressed Test, wrecking the route plan it had computed.
            AbrpApi.Response response = AbrpApi.verifyCredentials(apiKey, token);

            if (response.code == 200) return null;
            if (response.code == 401) return getString(R.string.conn_err_auth);
            return getString(R.string.conn_err_http, response.code);

        } catch (java.net.UnknownHostException e) {
            return getString(R.string.conn_err_no_internet);
        } catch (java.net.SocketTimeoutException e) {
            return getString(R.string.conn_err_timeout);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop polling off-screen: this activity has no reason to spend cycles then.
        uiHandler.removeCallbacks(uiRefresh);
    }

    // ---------- Cadence configuration ----------

    private void bindCadenceControls() {
        UploadSettings current = UploadSettings.from(prefs);

        String[] labels = new String[UploadSettings.INTERVAL_CHOICES_SEC.length];
        int selected = 0;
        for (int i = 0; i < UploadSettings.INTERVAL_CHOICES_SEC.length; i++) {
            int sec = UploadSettings.INTERVAL_CHOICES_SEC[i];
            labels[i] = sec >= 60
                    ? getString(R.string.interval_minutes, sec / 60)
                    : getString(R.string.interval_seconds, sec);
            if (sec == current.intervalSec) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalSpinner.setAdapter(adapter);
        intervalSpinner.setSelection(selected);
        intervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt(UploadSettings.KEY_INTERVAL_SEC,
                        UploadSettings.INTERVAL_CHOICES_SEC[position]).apply();
                // Takes effect on the next cycle — no service restart needed.
                AbrpUploadService.reloadSettings();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        boostSwitch.setChecked(current.boostLowSoc);
        lowSocLayout.setEnabled(current.boostLowSoc);
        lowSocInput.setEnabled(current.boostLowSoc);
        boostSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(UploadSettings.KEY_BOOST_LOW_SOC, checked).apply();
            lowSocLayout.setEnabled(checked);
            lowSocInput.setEnabled(checked);
            AbrpUploadService.reloadSettings();
        });

        lowSocInput.setText(String.valueOf(current.lowSocPercent));
        lowSocInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveLowSocThreshold();
        });
    }

    /** Clamped to 1-99: 0 would never trigger and 100 would boost permanently. */
    private void saveLowSocThreshold() {
        CharSequence raw = lowSocInput.getText();
        int value;
        try {
            value = Integer.parseInt(raw == null ? "" : raw.toString().trim());
        } catch (NumberFormatException e) {
            value = UploadSettings.DEFAULT_LOW_SOC_PERCENT;
        }
        value = Math.max(1, Math.min(99, value));
        lowSocInput.setText(String.valueOf(value));
        prefs.edit().putInt(UploadSettings.KEY_LOW_SOC_PERCENT, value).apply();
        AbrpUploadService.reloadSettings();
    }

    // ---------- Call log ----------

    private void refreshCallLog() {
        java.util.List<UploadLog.Entry> entries = AbrpUploadService.log().recent();
        if (entries.isEmpty()) {
            callLogText.setText(R.string.call_log_empty);
            return;
        }
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        StringBuilder sb = new StringBuilder();
        for (UploadLog.Entry entry : entries) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.success ? "OK  " : "ERR ")
              .append(format.format(new java.util.Date(entry.timestampMs)))
              .append("  ")
              // httpStatus 0 means the request never reached a server.
              .append(entry.httpStatus > 0 ? String.valueOf(entry.httpStatus) : "---")
              .append("  ")
              .append(entry.detail);
        }
        callLogText.setText(sb.toString());
    }

    private void setConnectionStatus(int color, String message) {
        connectionStatusRow.setVisibility(View.VISIBLE);
        connectionIndicator.getBackground().setTint(color);
        connectionStatusText.setText(message);
        connectionStatusText.setTextColor(color);
    }

    // ---------- Service status ----------

    private void refreshStatus() {
        // Live signal, not the preference: "service_running" stays stale-true after a
        // force-kill because onDestroy never ran.
        UploadLog.State state = AbrpUploadService.state();

        switch (state) {
            case ERROR:
                // Derived from the log, not from the last status alone: a single failed
                // upload is normal (tunnel, dead spot), three in a row is a problem.
                statusText.setText(getString(R.string.state_error,
                        AbrpUploadService.log().consecutiveFailures()));
                statusText.setTextColor(COLOR_ERROR);
                break;
            case RUNNING:
                String lastTime = prefs.getString("last_upload_time", null);
                statusText.setText(lastTime != null
                        ? getString(R.string.status_last_upload, lastTime,
                                getString(R.string.status_ok))
                        : getString(R.string.state_running));
                statusText.setTextColor(COLOR_OK);
                break;
            case STARTING:
                statusText.setText(R.string.state_starting);
                statusText.setTextColor(COLOR_OK);
                break;
            case STOPPED:
            default:
                statusText.setText(R.string.state_stopped);
                statusText.setTextColor(android.graphics.Color.GRAY);
                break;
        }
    }

    // ---------- Helpers ----------

    private String textOf(TextInputEditText field) {
        CharSequence text = field.getText();
        return text != null ? text.toString().trim() : "";
    }

    /**
     * Permissions that need a runtime grant (vs being granted at install time
     * via the platform signature). CAR_SPEED and CAR_ENERGY are dangerous-level
     * AAOS permissions — without these, the corresponding property reads throw
     * SecurityException and we have no SOC / speed data.
     */
    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY_PORTS",
    };

    private void requestLocationPermissionIfNeeded() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]),
                    LOCATION_PERMISSION_REQUEST);
        }
    }

    /**
     * A denied permission used to be silent: the service kept running and simply uploaded
     * nothing useful, with the UI claiming everything was fine. Say what was denied and
     * what it costs.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;

        boolean locationDenied = false;
        boolean carDataDenied  = false;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) continue;
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
                locationDenied = true;
            } else {
                carDataDenied = true;
            }
        }

        if (locationDenied && carDataDenied) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.perm_denied_all));
        } else if (locationDenied) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.perm_denied_location));
        } else if (carDataDenied) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.perm_denied_car));
        }
        refreshStatus();
    }
}
