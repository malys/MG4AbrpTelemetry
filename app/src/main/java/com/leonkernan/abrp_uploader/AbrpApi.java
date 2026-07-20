package com.leonkernan.abrp_uploader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ABRP telemetry endpoint.
 *
 * Credentials go in the POST body, never in the query string: a URL travels
 * through proxies, CDN access logs and crash reports, a body does not.
 */
final class AbrpApi {

    /** No query string: credentials travel in the body, so the URL is a constant. */
    static final String API_URL = "https://api.iternio.com/1/tlm/send";

    /**
     * Read-only endpoint used to validate credentials. The connection test must never
     * touch /tlm/send: posting a placeholder sample tells ABRP the car really is in that
     * state and corrupts the user's live route plan.
     */
    static final String VERIFY_URL = "https://api.iternio.com/1/tlm/get_next_charge";
    private static final int TIMEOUT_MS = 8_000;

    private AbrpApi() { }

    /** HTTP status plus response body. */
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * Validates credentials without writing anything. Read-only by construction: it is a
     * GET against {@link #VERIFY_URL}, so no telemetry is recorded whatever the outcome.
     *
     * Tension with T-912 (no credential in a URL): this endpoint only accepts the token as
     * a query parameter. The api_key is moved to the Authorization header, which is the
     * part we can keep out of access logs; the token cannot be, short of dropping the
     * read-only check and going back to writing fake telemetry. Sending one token in one
     * URL when the user presses Test beats corrupting their route plan on every press.
     */
    static Response verifyCredentials(String apiKey, String token) throws IOException {
        HttpURLConnection conn = null;
        try {
            String url = VERIFY_URL
                    + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name());
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "APIKEY " + apiKey);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int code = conn.getResponseCode();
            return new Response(code, readBody(conn, code));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Posts one telemetry sample. Throws on network failure. */
    static Response send(String apiKey, String token, String tlmJson) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            byte[] payload = formBody(apiKey, token, tlmJson).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int code = conn.getResponseCode();
            return new Response(code, readBody(conn, code));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Form-encoded request body carrying the credentials and the telemetry sample. */
    static String formBody(String apiKey, String token, String tlmJson) throws IOException {
        return "api_key=" + URLEncoder.encode(apiKey,  StandardCharsets.UTF_8.name())
             + "&token="  + URLEncoder.encode(token,   StandardCharsets.UTF_8.name())
             + "&tlm="    + URLEncoder.encode(tlmJson, StandardCharsets.UTF_8.name());
    }

    private static String readBody(HttpURLConnection conn, int code) {
        // getErrorStream() returns null on some non-200 responses; passing null to
        // InputStreamReader throws. Checked rather than left to the catch-all below.
        java.io.InputStream stream;
        try {
            stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        } catch (Exception e) {
            return "";
        }
        if (stream == null) return "";

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
