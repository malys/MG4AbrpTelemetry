package com.leonkernan.abrp_uploader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Storage for the ABRP credentials (api_key, token).
 *
 * They used to sit in plaintext in the regular SharedPreferences file, which
 * `adb backup` hands out. They now live in an AndroidKeyStore-backed encrypted
 * file, and the plaintext copy is wiped on first access.
 */
final class SecurePrefs {

    private static final String TAG        = "SecurePrefs";
    private static final String FILE_NAME  = "abrp_secure_prefs";
    private static final String PLAIN_NAME = "abrp_prefs";

    static final String KEY_API_KEY = "api_key";
    static final String KEY_TOKEN   = "token";

    private static SharedPreferences instance;

    private SecurePrefs() { }

    /**
     * Encrypted preferences, or the plaintext file if the crypto stack is
     * unavailable. Falling back keeps the app usable on a device with a broken
     * keystore instead of crashing it in a vehicle.
     */
    static synchronized SharedPreferences get(Context context) {
        if (instance != null) return instance;
        Context app = context.getApplicationContext();
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            instance = EncryptedSharedPreferences.create(
                    app,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            migratePlaintext(app, instance);
        } catch (Exception e) {
            Log.e(TAG, "Encrypted preferences unavailable, falling back to plaintext", e);
            instance = app.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE);
        }
        return instance;
    }

    /** Moves credentials written by an older version, then deletes the plaintext copy. */
    private static void migratePlaintext(Context context, SharedPreferences secure) {
        SharedPreferences plain = context.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE);
        String apiKey = plain.getString(KEY_API_KEY, null);
        String token  = plain.getString(KEY_TOKEN,   null);
        if (apiKey == null && token == null) return;

        SharedPreferences.Editor secureEdit = secure.edit();
        if (apiKey != null) secureEdit.putString(KEY_API_KEY, apiKey);
        if (token  != null) secureEdit.putString(KEY_TOKEN,   token);
        secureEdit.commit();

        plain.edit().remove(KEY_API_KEY).remove(KEY_TOKEN).commit();
    }
}
