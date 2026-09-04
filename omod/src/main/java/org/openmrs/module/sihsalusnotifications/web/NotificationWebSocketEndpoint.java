package org.openmrs.module.sihsalusnotifications.web;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.openmrs.module.sihsalusnotifications.api.NotificationSubscription;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotificationWebSocketEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(NotificationWebSocketEndpoint.class);

    static final String ENDPOINT_PATH = "/ws/sihsalus/notifications";

    private static final String DELIVERY_PROPERTY = NotificationWebSocketEndpoint.class.getName() + ".delivery";

    private static final Set<WebSocketDelivery> ACTIVE = Collections.newSetFromMap(
            new ConcurrentHashMap<WebSocketDelivery, Boolean>());

    private static final AtomicBoolean rejectionDiagnosticLogged = new AtomicBoolean();

    private static volatile NotificationService notificationService;

    private static volatile NotificationJsonWriter jsonWriter;

    private static volatile long connectionLifetimeMillis;

    private static volatile ScheduledExecutorService expirationScheduler;

    private final AuthenticatedSessionResolver sessionResolver = new AuthenticatedSessionResolver();

    private final TopicParser topicParser = new TopicParser();

    static synchronized void install(NotificationService service, NotificationJsonWriter writer,
            long lifetimeMillis) {
        if (expirationScheduler != null) {
            expirationScheduler.shutdownNow();
        }
        notificationService = service;
        jsonWriter = writer;
        connectionLifetimeMillis = lifetimeMillis;
        rejectionDiagnosticLogged.set(false);
        expirationScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "sihsalus-notification-websocket-expiry");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    static synchronized void shutdown() {
        for (WebSocketDelivery delivery : ACTIVE) {
            delivery.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "module stopping"));
        }
        ACTIVE.clear();
        notificationService = null;
        jsonWriter = null;
        if (expirationScheduler != null) {
            expirationScheduler.shutdownNow();
            expirationScheduler = null;
        }
        WebSocketConnectionContexts.clear();
    }

    @Override
    public void onOpen(final Session session, EndpointConfig config) {
        String connectionId = firstParameter(session.getRequestParameterMap(), "connectionId");
        WebSocketConnectionContext connection = WebSocketConnectionContexts.claim(connectionId);
        SubscriberIdentity identity = connection == null ? null
                : sessionResolver.resolve(connection.getHttpSession());
        NotificationService service = notificationService;
        NotificationJsonWriter writer = jsonWriter;
        ScheduledExecutorService scheduler = expirationScheduler;
        long lifetimeMillis = connectionLifetimeMillis;
        if (connection == null || !connection.isOriginAllowed() || identity == null
                || service == null || writer == null || scheduler == null) {
            if (rejectionDiagnosticLogged.compareAndSet(false, true)) {
                log.warn("Rejecting a notification WebSocket connection "
                        + "[handshakeContext={}, httpSession={}, originAllowed={}, identity={}, runtimeReady={}]",
                        connection != null,
                        connection != null && connection.getHttpSession() != null,
                        connection != null && connection.isOriginAllowed(),
                        identity != null,
                        service != null && writer != null && scheduler != null);
            }
            close(session, CloseReason.CloseCodes.VIOLATED_POLICY, "unauthorized");
            return;
        }

        final Set<String> topics;
        try {
            topics = topicParser.parse(session.getRequestParameterMap());
        } catch (IllegalArgumentException exception) {
            close(session, CloseReason.CloseCodes.VIOLATED_POLICY, "invalid topics");
            return;
        }

        session.setMaxIdleTimeout(lifetimeMillis);
        session.setMaxTextMessageBufferSize(16);
        final WebSocketDelivery delivery = new WebSocketDelivery(session, writer);
        session.getUserProperties().put(DELIVERY_PROPERTY, delivery);
        ACTIVE.add(delivery);
        try {
            NotificationSubscription subscription = service.subscribe(identity, topics, delivery);
            delivery.attach(subscription);
            delivery.expireAfter(scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    delivery.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "reauthenticate"));
                }
            }, lifetimeMillis, TimeUnit.MILLISECONDS));
        } catch (IllegalStateException exception) {
            ACTIVE.remove(delivery);
            delivery.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(1013), "capacity reached"));
            return;
        } catch (RuntimeException exception) {
            ACTIVE.remove(delivery);
            delivery.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION,
                    "endpoint unavailable"));
            return;
        }
        session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                if ("ping".equals(message)) {
                    delivery.pong();
                } else {
                    delivery.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,
                            "read-only transport"));
                }
            }
        });
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        WebSocketDelivery delivery = delivery(session);
        if (delivery != null) {
            ACTIVE.remove(delivery);
            delivery.cleanup();
        }
    }

    @Override
    public void onError(Session session, Throwable throwable) {
        WebSocketDelivery delivery = delivery(session);
        if (delivery != null) {
            ACTIVE.remove(delivery);
            delivery.cleanup();
        }
    }

    private WebSocketDelivery delivery(Session session) {
        if (session == null) {
            return null;
        }
        Object value = session.getUserProperties().get(DELIVERY_PROPERTY);
        return value instanceof WebSocketDelivery ? (WebSocketDelivery) value : null;
    }

    private String firstParameter(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters == null ? null : parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private void close(Session session, CloseReason.CloseCode code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (Exception ignored) {
            // The failed handshake has no usable connection to retain.
        }
    }
}
