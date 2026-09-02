package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class WebSocketOriginPolicyTest {

    @Test
    public void permitsDirectAndForwardedSameOriginRequests() throws Exception {
        WebSocketOriginPolicy policy = new WebSocketOriginPolicy(Collections.<String>emptySet());
        assertTrue(policy.isAllowed(new URI("ws://localhost:8080/openmrs/ws/sihsalus/notifications"),
                headers("Origin", "http://localhost:8080", "Host", "localhost:8080")));

        Map<String, List<String>> forwarded = headers(
                "Origin", "https://sihsalus.hsc",
                "Host", "sihsalus.hsc");
        forwarded.put("X-Forwarded-Proto", Collections.singletonList("https"));
        assertTrue(policy.isAllowed(new URI("ws://backend:8080/openmrs/ws/sihsalus/notifications"),
                forwarded));
    }

    @Test
    public void permitsOnlyExactConfiguredAdditionalOrigins() throws Exception {
        WebSocketOriginPolicy policy = new WebSocketOriginPolicy(
                Collections.singleton("https://trusted.example"));

        assertTrue(policy.isAllowed(new URI("ws://backend/openmrs/ws/sihsalus/notifications"),
                headers("Origin", "https://trusted.example/", "Host", "backend")));
        assertFalse(policy.isAllowed(new URI("ws://backend/openmrs/ws/sihsalus/notifications"),
                headers("Origin", "https://trusted.example.evil", "Host", "backend")));
    }

    @Test
    public void rejectsMissingMalformedAndCrossSiteOrigins() throws Exception {
        WebSocketOriginPolicy policy = new WebSocketOriginPolicy(Collections.<String>emptySet());
        URI request = new URI("ws://sihsalus.hsc/openmrs/ws/sihsalus/notifications");

        assertFalse(policy.isAllowed(request, headers("Host", "sihsalus.hsc")));
        assertFalse(policy.isAllowed(request,
                headers("Origin", "https://attacker.example", "Host", "sihsalus.hsc")));
        assertFalse(policy.isAllowed(request,
                headers("Origin", "https://user@sihsalus.hsc", "Host", "sihsalus.hsc")));
    }

    private Map<String, List<String>> headers(String... pairs) {
        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        for (int index = 0; index < pairs.length; index += 2) {
            headers.put(pairs[index], Arrays.asList(pairs[index + 1]));
        }
        return headers;
    }
}
