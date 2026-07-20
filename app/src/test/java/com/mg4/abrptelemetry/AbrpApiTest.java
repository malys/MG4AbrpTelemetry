package com.mg4.abrptelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * [T-912] Credentials must reach ABRP in the request body. A URL is logged by
 * proxies, CDNs and crash reporters; a body is not.
 */
public class AbrpApiTest {

    @Test
    public void endpointUrlCarriesNoCredentials() {
        assertFalse(AbrpApi.API_URL.contains("?"));
        assertFalse(AbrpApi.API_URL.contains("api_key"));
        assertFalse(AbrpApi.API_URL.contains("token"));
    }

    @Test
    public void endpointIsHttps() {
        assertTrue(AbrpApi.API_URL.startsWith("https://"));
        assertTrue(AbrpApi.VERIFY_URL.startsWith("https://"));
    }

    /** [T-913] The Test button must not be able to write telemetry. */
    @Test
    public void credentialCheckDoesNotTargetTheTelemetryWriteEndpoint() {
        assertFalse("le test de connexion ne doit jamais viser /tlm/send",
                AbrpApi.VERIFY_URL.contains("/tlm/send"));
        assertTrue(AbrpApi.VERIFY_URL.contains("get_next_charge"));
    }

    @Test
    public void bodyCarriesAllThreeParameters() throws Exception {
        String body = AbrpApi.formBody("KEY", "TOK", "{\"utc\":1}");
        assertTrue(body.contains("api_key=KEY"));
        assertTrue(body.contains("token=TOK"));
        assertTrue(body.contains("tlm="));
    }

    @Test
    public void bodyIsFormEncoded() throws Exception {
        // A token with URL-significant characters must not break the body layout.
        String body = AbrpApi.formBody("a+b/c", "d&e=f", "{\"utc\":1}");
        assertEquals("api_key=a%2Bb%2Fc&token=d%26e%3Df&tlm=%7B%22utc%22%3A1%7D", body);
    }
}
