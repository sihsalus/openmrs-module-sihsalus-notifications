package org.openmrs.module.sihsalusnotifications.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpSession;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

final class OpenmrsHandshakeConfigurator extends ServerEndpointConfig.Configurator {

    private final WebSocketOriginPolicy originPolicy;

    OpenmrsHandshakeConfigurator(WebSocketOriginPolicy originPolicy) {
        this.originPolicy = originPolicy;
    }

    @Override
    public boolean checkOrigin(String originHeaderValue) {
        // The request host is unavailable here. Exact validation happens in modifyHandshake.
        return true;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
            HandshakeResponse response) {
        String connectionId = firstParameter(request.getParameterMap(), "connectionId");
        if (connectionId == null) {
            // Some JSR 356 implementations expose query parameters only through
            // the request URI, so retain the portable fallback.
            connectionId = queryParameter(request.getRequestURI(), "connectionId");
        }
        if (!isUuid(connectionId)) {
            return;
        }
        Object session = request.getHttpSession();
        HttpSession httpSession = session instanceof HttpSession ? (HttpSession) session : null;
        boolean originAllowed = originPolicy.isAllowed(request.getRequestURI(), request.getHeaders());
        WebSocketConnectionContexts.bindHandshake(connectionId, sessionId(httpSession), originAllowed);
    }

    private String firstParameter(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters == null ? null : parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String queryParameter(URI uri, String name) {
        if (uri == null || uri.getRawQuery() == null) {
            return null;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equals(decode(rawName))) {
                return separator < 0 ? "" : decode(pair.substring(separator + 1));
            }
        }
        return null;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return "";
        }
    }

    private boolean isUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String sessionId(HttpSession session) {
        try {
            return session == null ? null : session.getId();
        } catch (IllegalStateException exception) {
            return null;
        }
    }
}
