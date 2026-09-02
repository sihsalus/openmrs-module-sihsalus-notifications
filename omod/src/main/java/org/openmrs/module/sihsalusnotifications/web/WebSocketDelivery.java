package org.openmrs.module.sihsalusnotifications.web;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.websocket.CloseReason;
import javax.websocket.SendResult;
import javax.websocket.Session;

import org.openmrs.module.sihsalusnotifications.NotificationConstants;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;
import org.openmrs.module.sihsalusnotifications.api.NotificationListener;
import org.openmrs.module.sihsalusnotifications.api.NotificationSubscription;

final class WebSocketDelivery implements NotificationListener {

    private final Session session;

    private final NotificationJsonWriter jsonWriter;

    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<String>(
            NotificationConstants.MAX_PENDING_EVENTS_PER_CONNECTION);

    private final AtomicBoolean sending = new AtomicBoolean(false);

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile NotificationSubscription subscription;

    private volatile ScheduledFuture<?> expiration;

    WebSocketDelivery(Session session, NotificationJsonWriter jsonWriter) {
        this.session = session;
        this.jsonWriter = jsonWriter;
    }

    void attach(NotificationSubscription subscription) {
        this.subscription = subscription;
        if (closed.get()) {
            subscription.close();
        }
    }

    void expireAfter(ScheduledFuture<?> expiration) {
        this.expiration = expiration;
        if (closed.get()) {
            expiration.cancel(false);
        }
    }

    @Override
    public void onNotification(NotificationEvent event) {
        enqueue(jsonWriter.write(event));
    }

    void pong() {
        enqueue("{\"type\":\"pong\"}");
    }

    void close(CloseReason reason) {
        cleanup();
        if (session.isOpen()) {
            try {
                session.close(reason);
            } catch (IOException ignored) {
                // The connection is already unusable.
            }
        }
    }

    void cleanup() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.clear();
        ScheduledFuture<?> scheduledExpiration = expiration;
        if (scheduledExpiration != null) {
            scheduledExpiration.cancel(false);
        }
        NotificationSubscription current = subscription;
        if (current != null) {
            current.close();
        }
    }

    private void enqueue(String message) {
        if (closed.get()) {
            return;
        }
        if (!pending.offer(message)) {
            close(new CloseReason(CloseReason.CloseCodes.getCloseCode(1013), "client too slow"));
            return;
        }
        sendNext();
    }

    private void sendNext() {
        if (closed.get() || !sending.compareAndSet(false, true)) {
            return;
        }
        final String message = pending.poll();
        if (message == null) {
            sending.set(false);
            if (!pending.isEmpty()) {
                sendNext();
            }
            return;
        }
        try {
            session.getAsyncRemote().sendText(message, this::sent);
        } catch (RuntimeException exception) {
            sending.set(false);
            close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "delivery failed"));
        }
    }

    private void sent(SendResult result) {
        sending.set(false);
        if (!result.isOK()) {
            close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "delivery failed"));
            return;
        }
        sendNext();
    }
}
