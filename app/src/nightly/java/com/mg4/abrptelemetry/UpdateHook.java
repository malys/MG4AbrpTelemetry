package com.mg4.abrptelemetry;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Nightly channel: checks GitHub pre-releases and downloads a newer nightly APK.
 *
 * The install itself stays manual — the user taps the downloaded file. The app is not
 * privileged enough to install silently, and asking for that privilege to save one tap on
 * a test channel is not a trade worth making.
 */
final class UpdateHook {

    private static final String TAG = "UpdateHook";

    private static String safeVersionComponent(String versionName) {
        if (versionName == null || versionName.isEmpty()) return "unknown";
        String normalized = versionName.toLowerCase(Locale.US);
        return normalized.replaceAll("[^a-z0-9._-]", "_");
    }

    private UpdateHook() { }

    static boolean isSupported() { return true; }

    /** Fire-and-forget check. Network work runs off the main thread. */
    static void checkInBackground(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String current = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0).versionName;
                if (current == null) return;

                OtaUpdater.Update update = OtaUpdater.check(current);
                if (update == null) {
                    Log.i(TAG, "No newer nightly than " + current);
                    return;
                }

                // Anything already downloaded is verified before the user is pointed at it:
                // a file in public Downloads can be swapped by another app.
                String safeVersionName = safeVersionComponent(update.versionName);
                File existing = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MG4AbrpTelemetry-nightly-" + safeVersionName + ".apk");
                if (existing.isFile()) {
                    if (!OtaUpdater.signatureMatchesRunningApp(app, existing)) {
                        // Signed by someone else: delete rather than leave it where a user
                        // might tap it.
                        boolean deleted = existing.delete();
                        Log.w(TAG, "Rejected a foreign-signed update (deleted=" + deleted + ")");
                        return;
                    }
                    Log.i(TAG, "Update already downloaded and verified: " + existing.getName());
                    return;
                }

                OtaUpdater.download(app, update);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(app,
                                "Downloading nightly " + update.versionName,
                                Toast.LENGTH_LONG).show());
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Cannot read our own version", e);
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
            }
        }, "ota-check").start();
    }
}
