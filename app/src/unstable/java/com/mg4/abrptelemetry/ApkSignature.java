package com.mg4.abrptelemetry;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** SHA-256 fingerprints of the certificates signing an APK. Unstable builds only. */
final class ApkSignature {

    private static final String TAG = "ApkSignature";

    private ApkSignature() { }

    /** Fingerprints of the APK file at [path]; empty when it cannot be read. */
    static Set<String> of(Context context, String path) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageArchiveInfo(path, flags());
            return digests(info);
        } catch (Exception e) {
            Log.w(TAG, "Cannot read archive signature: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /** Fingerprints of the running app; empty when they cannot be read. */
    static Set<String> ofPackage(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), flags());
            return digests(info);
        } catch (Exception e) {
            Log.w(TAG, "Cannot read our own signature: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    @SuppressWarnings("deprecation")
    private static int flags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> digests(PackageInfo info) {
        if (info == null) return Collections.emptySet();

        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) return Collections.emptySet();
            signatures = signingInfo.hasMultipleSigners()
                    ? signingInfo.getApkContentsSigners()
                    : signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null) return Collections.emptySet();

        Set<String> out = new HashSet<>();
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                if (signature == null) continue;
                StringBuilder hex = new StringBuilder();
                for (byte b : sha256.digest(signature.toByteArray())) {
                    hex.append(String.format("%02x", b));
                }
                out.add(hex.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "Digest failed: " + e.getMessage());
            return Collections.emptySet();
        }
        return out;
    }
}
