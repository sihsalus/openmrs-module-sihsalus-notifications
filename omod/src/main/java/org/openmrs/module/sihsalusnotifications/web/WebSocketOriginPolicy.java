package org.openmrs.module.sihsalusnotifications.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WebSocketOriginPolicy {

    private final Set<String> additionalAllowedOrigins;

    WebSocketOriginPolicy(Set<String> additionalAllowedOrigins) {
        this.additionalAllowedOrigins = additionalAllowedOrigins == null
                ? Collections.<String>emptySet() : additionalAllowedOrigins;
    }

    boolean isAllowed(URI requestUri, Map<String, List<String>> headers) {
        String origin = first(headers, "Origin");
        String normalizedOrigin = normalizeOrigin(origin);
        if (normalizedOrigin == null) {
            return false;
        }
        if (additionalAllowedOrigins.contains(normalizedOrigin)) {
            return true;
        }

        String host = first(headers, "Host");
        if (host == null || host.indexOf(',') >= 0 || host.indexOf('/') >= 0) {
            return false;
        }

        String scheme = first(headers, "X-Forwarded-Proto");
        if (scheme != null && scheme.indexOf(',') >= 0) {
            scheme = scheme.substring(0, scheme.indexOf(','));
        }
        if (scheme == null || scheme.trim().isEmpty()) {
            scheme = requestUri == null ? null : requestUri.getScheme();
        }
        if (scheme == null || scheme.trim().isEmpty()) {
            scheme = normalizedOrigin.substring(0, normalizedOrigin.indexOf("://"));
        }
        if ("ws".equalsIgnoreCase(scheme)) {
            scheme = "http";
        } else if ("wss".equalsIgnoreCase(scheme)) {
            scheme = "https";
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        return normalizedOrigin.equals(scheme.toLowerCase(Locale.ROOT) + "://"
                + host.trim().toLowerCase(Locale.ROOT));
    }

    static String normalizeConfiguredOrigin(String value) {
        return normalizeOrigin(value);
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if ((scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)))
                    || uri.getRawAuthority() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                return null;
            }
            return scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
