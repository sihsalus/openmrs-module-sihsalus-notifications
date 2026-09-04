package org.openmrs.module.sihsalusnotifications.web;

import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

final class WebSocketConnectionContext {

    private final SubscriberIdentity identity;

    private final String httpSessionId;

    private final long createdAt;

    private boolean handshakeBound;

    private boolean originAllowed;

    WebSocketConnectionContext(SubscriberIdentity identity, String httpSessionId, long createdAt) {
        this.identity = identity;
        this.httpSessionId = httpSessionId;
        this.createdAt = createdAt;
    }

    SubscriberIdentity getIdentity() {
        return identity;
    }

    boolean belongsToSession(String candidateSessionId) {
        return candidateSessionId != null && httpSessionId.equals(candidateSessionId);
    }

    synchronized boolean bindHandshake(String candidateSessionId, boolean allowedOrigin) {
        if (handshakeBound || candidateSessionId == null
                || !httpSessionId.equals(candidateSessionId)) {
            return false;
        }
        handshakeBound = true;
        originAllowed = allowedOrigin;
        return true;
    }

    synchronized boolean isHandshakeBound() {
        return handshakeBound;
    }

    synchronized boolean isOriginAllowed() {
        return originAllowed;
    }

    long getCreatedAt() {
        return createdAt;
    }
}
