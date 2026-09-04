package org.openmrs.module.sihsalusnotifications.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

final class WebSocketConnectionContexts {

    private static final long CONTEXT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final int MAX_PENDING_CONTEXTS = 1000;

    private static final int MAX_PENDING_CONTEXTS_PER_SESSION = 8;

    private static final ConcurrentMap<String, WebSocketConnectionContext> CONTEXTS =
            new ConcurrentHashMap<String, WebSocketConnectionContext>();

    private WebSocketConnectionContexts() {
    }

    static String issue(SubscriberIdentity identity, String httpSessionId) {
        return issue(identity, httpSessionId, System.currentTimeMillis());
    }

    static synchronized String issue(SubscriberIdentity identity, String httpSessionId, long now) {
        cleanupExpired(now);
        if (identity == null || httpSessionId == null || httpSessionId.trim().isEmpty()
                || CONTEXTS.size() >= MAX_PENDING_CONTEXTS) {
            return null;
        }
        int pendingForSession = 0;
        for (WebSocketConnectionContext context : CONTEXTS.values()) {
            if (context.belongsToSession(httpSessionId)
                    && ++pendingForSession >= MAX_PENDING_CONTEXTS_PER_SESSION) {
                return null;
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String connectionId = UUID.randomUUID().toString();
            WebSocketConnectionContext context =
                    new WebSocketConnectionContext(identity, httpSessionId, now);
            if (CONTEXTS.putIfAbsent(connectionId, context) == null) {
                return connectionId;
            }
        }
        return null;
    }

    static boolean bindHandshake(String connectionId, String httpSessionId,
            boolean originAllowed) {
        return bindHandshake(connectionId, httpSessionId, originAllowed,
                System.currentTimeMillis());
    }

    static boolean bindHandshake(String connectionId, String httpSessionId,
            boolean originAllowed, long now) {
        if (connectionId == null) {
            return false;
        }
        WebSocketConnectionContext context = CONTEXTS.get(connectionId);
        if (context == null) {
            return false;
        }
        if (now - context.getCreatedAt() > CONTEXT_TTL_MILLIS) {
            CONTEXTS.remove(connectionId, context);
            return false;
        }
        return context.bindHandshake(httpSessionId, originAllowed);
    }

    static WebSocketConnectionContext claim(String connectionId) {
        return claim(connectionId, System.currentTimeMillis());
    }

    static WebSocketConnectionContext claim(String connectionId, long now) {
        if (connectionId == null) {
            return null;
        }
        WebSocketConnectionContext context = CONTEXTS.remove(connectionId);
        if (context == null || now - context.getCreatedAt() > CONTEXT_TTL_MILLIS) {
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
