package org.openmrs.module.sihsalusnotifications;

public final class NotificationConstants {

    public static final String MODULE_ID = "sihsalusnotifications";

    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    public static final int MAX_SUBSCRIBERS = 500;

    public static final int MAX_TOPICS_PER_SUBSCRIPTION = 16;

    public static final int MAX_PENDING_EVENTS_PER_CONNECTION = 100;

    public static final int MAX_REPLAY_EVENTS = 1000;

    public static final int MAX_REPLAY_PAYLOAD_BYTES = 4 * 1024 * 1024;

    public static final long REPLAY_WINDOW_MILLIS = 5L * 60L * 1000L;

    public static final int MAX_CONCURRENT_SSE_CONNECTIONS = 50;

    private NotificationConstants() {
    }
}
