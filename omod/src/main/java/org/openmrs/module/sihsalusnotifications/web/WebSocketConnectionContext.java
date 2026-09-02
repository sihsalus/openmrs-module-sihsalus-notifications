package org.openmrs.module.sihsalusnotifications.web;

import javax.servlet.http.HttpSession;

final class WebSocketConnectionContext {

    private final HttpSession httpSession;

    private final boolean originAllowed;

    private final long createdAt;

    WebSocketConnectionContext(HttpSession httpSession, boolean originAllowed, long createdAt) {
        this.httpSession = httpSession;
        this.originAllowed = originAllowed;
        this.createdAt = createdAt;
    }

    HttpSession getHttpSession() {
        return httpSession;
    }

    boolean isOriginAllowed() {
        return originAllowed;
    }

    long getCreatedAt() {
        return createdAt;
    }
}
