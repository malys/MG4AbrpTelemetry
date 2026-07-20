package com.mg4.abrptelemetry;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Over-the-air updater — NIGHTLY BUILDS ONLY.
 *
 * The stable channel deliberately has no self-update path: this class does not exist in a
 * stable build. Nightly testers get updates without manual work, and accept that channel's
 * risk.
 *
 * Security posture is the one MG4Control settled on in its own OTA work:
 *  - the APK URL comes from a remote JSON document and is never trusted: https only,
 *    exact-match host allowlist, checked again before it reaches the system downloader;
 *  - the downloaded APK must be signed by the same certificate as the running app, or it
 *    is deleted rather than offered for install;
 *  - both checks fail closed.
 */
final class OtaUpdater {

    private static final String TAG = "OtaUpdater";

    /** Pre-releases live here; the nightly channel tracks them. */
    private static final String RELEASES_API =
            "https://api.github.com/repos/malys/MG4AbrpTelemetry/releases";

    /**
     * Hosts an update may come from. The githubusercontent entries are the CDNs GitHub
     * redirects release-asset downloads to; without them the download fails.
     */
    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com");

    private static final int TIMEOUT_MS = 10_000;

    private OtaUpdater() { }

    /** Result of a check: null when there is nothing newer or the check failed. */
    static final class Update {
        final String versionName;
        final String apkUrl;

        Update(String versionName, String apkUrl) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }

    /**
     * True if [url] is https and points at an allowed host.
     *
     * Rejects http (including an https -> http downgrade), unknown hosts, unparsable
     * URLs, and lookalikes such as "github.com.attacker.net" — the host match is exact,
     * never a suffix test.
     */
    static boolean isAllowedUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) return false;
        String host = uri.getHost();
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(java.util.Locale.US));
    }

    /**
     * Numeric core of a version: "v1.2.3-nightly" -> [1, 2, 3].
     *
     * A segment with no digits becomes 0 rather than being dropped, so later segments do
     * not shift left and turn a patch into a minor.
     */
    static int[] segments(String version) {
        String core = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1) : version;
        int cut = core.indexOf('+');
        if (cut >= 0) core = core.substring(0, cut);
        cut = core.indexOf('-');
        if (cut >= 0) core = core.substring(0, cut);

        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int digits = 0;
            while (digits < parts[i].length() && Character.isDigit(parts[i].charAt(digits))) digits++;
            try {
                out[i] = digits == 0 ? 0 : Integer.parseInt(parts[i].substring(0, digits));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** True if [remote] is a strictly higher version than [current]. */
    static boolean isNewer(String remote, String current) {
        int[] r = segments(remote);
        int[] c = segments(current);
        for (int i = 0; i < Math.max(r.length, c.length); i++) {
            int rv = i < r.length ? r[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (rv > cv) return true;
            if (rv < cv) return false;
        }
        return false;
    }

    /**
     * Asks GitHub for the newest pre-release and returns it if it beats [currentVersion].
     * Runs on the caller's thread — never call from the main thread.
     */
    static Update check(String currentVersion) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "MG4AbrpTelemetry-Android");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "Release API returned " + conn.getResponseCode());
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }

            JSONArray releases = new JSONArray(body.toString());
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                // Nightly tracks pre-releases; a stable tag is not this channel's business.
                if (!release.optBoolean("prerelease", false)) continue;

                String tag = release.optString("tag_name", "");
                if (!isNewer(tag, currentVersion)) continue;

                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int a = 0; a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    String name = asset.optString("name", "");
                    if (!name.toLowerCase(java.util.Locale.US).endsWith(".apk")) continue;
                    if (!name.contains("nightly")) continue;

                    String url = asset.optString("browser_download_url", "");
                    if (!isAllowedUrl(url)) {
                        Log.w(TAG, "Rejected update URL from an unexpected host: " + url);
                        continue;
                    }
                    return new Update(tag, url);
                }
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Update check failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Queues the download. The URL is re-checked here: a remote URL is never handed to a
     * system component on the strength of an earlier check alone.
     */
    static void download(Context context, Update update) {
        if (!isAllowedUrl(update.apkUrl)) {
            Log.w(TAG, "Refusing to download from " + update.apkUrl);
            return;
        }
        String fileName = "MG4AbrpTelemetry-nightly-" + update.versionName + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("MG4 ABRP Telemetry " + update.versionName)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setMimeType("application/vnd.android.package-archive");

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(request);
        Log.i(TAG, "Update queued: " + fileName);
    }

    /**
     * True if [apk] is signed by the same certificate as the running app.
     *
     * Fail closed: an unreadable archive, a missing signature or a failed API call all
     * return false. The caller must delete the file rather than offer it for install.
     */
    static boolean signatureMatchesRunningApp(Context context, File apk) {
        java.util.Set<String> archive = ApkSignature.of(context, apk.getAbsolutePath());
        java.util.Set<String> installed = ApkSignature.ofPackage(context);
        boolean ok = !archive.isEmpty() && !installed.isEmpty() && archive.equals(installed);
        if (!ok) {
            Log.w(TAG, "Signature mismatch — refusing update ("
                    + archive.size() + " vs " + installed.size() + " cert(s))");
        }
        return ok;
    }
}
