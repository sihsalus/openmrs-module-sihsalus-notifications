package org.openmrs.module.sihsalusnotifications.web;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openmrs.api.context.Context;

final class NotificationRuntimeSettings {

    private static final String ALLOWED_ORIGINS = "sihsalusnotifications.allowedOrigins";

    private static final String SSE_CONNECTION_SECONDS = "sihsalusnotifications.sseConnectionSeconds";

    private static final String WEBSOCKET_CONNECTION_SECONDS =
            "sihsalusnotifications.websocketConnectionSeconds";

    Set<String> getAdditionalAllowedOrigins() {
        String value = getGlobalProperty(ALLOWED_ORIGINS, "");
        if (value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> origins = new HashSet<String>();
        for (String origin : value.split(",")) {
            String normalized = WebSocketOriginPolicy.normalizeConfiguredOrigin(origin);
            if (normalized != null) {
                origins.add(normalized);
            }
        }
        return Collections.unmodifiableSet(origins);
    }

    int getSseConnectionSeconds() {
        return boundedInteger(SSE_CONNECTION_SECONDS, 25, 5, 120);
    }

    long getWebSocketConnectionMillis() {
        return boundedInteger(WEBSOCKET_CONNECTION_SECONDS, 300, 30, 1800) * 1000L;
    }

    private int boundedInteger(String property, int defaultValue, int minimum, int maximum) {
        String raw = getGlobalProperty(property, Integer.toString(defaultValue));
        try {
            int value = Integer.parseInt(raw);
            return value < minimum || value > maximum ? defaultValue : value;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String getGlobalProperty(String name, String defaultValue) {
        try {
            String value = Context.getAdministrationService().getGlobalProperty(name);
            return value == null ? defaultValue : value;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
