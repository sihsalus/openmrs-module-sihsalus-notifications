package org.openmrs.module.sihsalusnotifications.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

final class WebSocketConnectionContexts {

    private static final long CONTEXT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final int MAX_PENDING_CONTEXTS = 1000;

    private static final ConcurrentMap<String, WebSocketConnectionContext> CONTEXTS =
            new ConcurrentHashMap<String, WebSocketConnectionContext>();

    private WebSocketConnectionContexts() {
    }

    static boolean register(String connectionId, WebSocketConnectionContext context) {
        cleanupExpired(context.getCreatedAt());
        return CONTEXTS.size() < MAX_PENDING_CONTEXTS
                && CONTEXTS.putIfAbsent(connectionId, context) == null;
    }

    static WebSocketConnectionContext claim(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        WebSocketConnectionContext context = CONTEXTS.remove(connectionId);
        if (context == null || System.currentTimeMillis() - context.getCreatedAt() > CONTEXT_TTL_MILLIS) {
            return null;
        }
        return context;
    }

    static void clear() {
        CONTEXTS.clear();
    }

    private static void cleanupExpired(long now) {
        for (Map.Entry<String, WebSocketConnectionContext> entry : CONTEXTS.entrySet()) {
            WebSocketConnectionContext context = entry.getValue();
            if (now - context.getCreatedAt() > CONTEXT_TTL_MILLIS) {
                CONTEXTS.remove(entry.getKey(), context);
            }
        }
    }
}
