package org.openmrs.module.sihsalusnotifications.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        String connectionId = queryParameter(request.getRequestURI(), "connectionId");
        if (!isUuid(connectionId)) {
            return;
        }
        Object session = request.getHttpSession();
        HttpSession httpSession = session instanceof HttpSession ? (HttpSession) session : null;
        boolean originAllowed = originPolicy.isAllowed(request.getRequestURI(), request.getHeaders());
        WebSocketConnectionContexts.register(connectionId,
                new WebSocketConnectionContext(httpSession, originAllowed, System.currentTimeMillis()));
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
}
