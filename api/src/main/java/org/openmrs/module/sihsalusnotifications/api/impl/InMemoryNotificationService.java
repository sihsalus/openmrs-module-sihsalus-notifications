package org.openmrs.module.sihsalusnotifications.api.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.ValidationException;
import org.openmrs.module.sihsalusnotifications.NotificationConstants;
import org.openmrs.module.sihsalusnotifications.api.NotificationClock;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;
import org.openmrs.module.sihsalusnotifications.api.NotificationListener;
import org.openmrs.module.sihsalusnotifications.api.NotificationMetrics;
import org.openmrs.module.sihsalusnotifications.api.NotificationRequest;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.openmrs.module.sihsalusnotifications.api.NotificationSubscription;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotificationService.class);

    private static final Pattern MACHINE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    private final ConcurrentMap<String, Subscriber> subscribers =
            new ConcurrentHashMap<String, Subscriber>();

    private final Deque<NotificationEvent> history = new ArrayDeque<NotificationEvent>();

    private final AtomicLong publishedEventCount = new AtomicLong();

    private final AtomicLong liveDeliveryCount = new AtomicLong();

    private final AtomicLong replayDeliveryCount = new AtomicLong();

    private final AtomicLong deliveryFailureCount = new AtomicLong();

    private final AtomicLong replayMissCount = new AtomicLong();

    private int retainedPayloadBytes;

    private NotificationClock clock = new SystemNotificationClock();

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public synchronized void onStartup() {
        subscribers.clear();
        resetHistoryAndMetrics();
    }

    @Override
    public synchronized void onShutdown() {
        subscribers.clear();
        resetHistoryAndMetrics();
    }

    @Override
    public synchronized NotificationEvent publish(NotificationRequest request) {
        validate(request);
        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID().toString(),
                request.getTopic(),
                request.getType(),
                request.getPayloadJson(),
                emptyToNull(request.getRecipientUserUuid()),
                emptyToNull(request.getRequiredPrivilege()),
                emptyToNull(request.getScopeLocationUuid()),
                clock.currentTimeMillis());

        retain(event);
        publishedEventCount.incrementAndGet();

        for (Map.Entry<String, Subscriber> entry : subscribers.entrySet()) {
            Subscriber subscriber = entry.getValue();
            if (!subscriber.accepts(event)) {
                continue;
            }
            try {
                subscriber.listener.onNotification(event);
                liveDeliveryCount.incrementAndGet();
            } catch (RuntimeException exception) {
                subscribers.remove(entry.getKey(), subscriber);
                deliveryFailureCount.incrementAndGet();
                log.warn("Removing a realtime notification subscriber after delivery failure");
            }
        }
        return event;
    }

    @Override
    public synchronized NotificationSubscription subscribe(SubscriberIdentity identity, Set<String> topics,
            NotificationListener listener) {
        return subscribe(identity, topics, listener, null);
    }

    @Override
    public synchronized NotificationSubscription subscribe(SubscriberIdentity identity, Set<String> topics,
            NotificationListener listener, String lastEventId) {
        if (identity == null || listener == null) {
            throw new IllegalArgumentException("Subscriber identity and listener are required");
        }
        Set<String> normalizedTopics = normalizeTopics(topics);
        String normalizedLastEventId = emptyToNull(lastEventId);
        if (normalizedLastEventId != null && !UUID_PATTERN.matcher(normalizedLastEventId).matches()) {
            throw new IllegalArgumentException("Last notification event ID must be a UUID");
        }
        if (subscribers.size() >= NotificationConstants.MAX_SUBSCRIBERS) {
            throw new IllegalStateException("Realtime subscriber capacity reached");
        }

        String id = UUID.randomUUID().toString();
        Subscriber subscriber = new Subscriber(identity, normalizedTopics, listener);
        subscribers.put(id, subscriber);
        try {
            boolean replayComplete = replay(subscriber, normalizedLastEventId);
            return new InMemorySubscription(id, subscriber, replayComplete);
        } catch (RuntimeException exception) {
            subscribers.remove(id, subscriber);
            throw exception;
        }
    }

    @Override
    public int getSubscriberCount() {
        return subscribers.size();
    }

    @Override
    public synchronized NotificationMetrics getMetrics() {
        pruneExpiredHistory(clock.currentTimeMillis());
        return new NotificationMetrics(
                subscribers.size(),
                history.size(),
                publishedEventCount.get(),
                liveDeliveryCount.get(),
                replayDeliveryCount.get(),
                deliveryFailureCount.get(),
                replayMissCount.get());
    }

    private void validate(NotificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Notification request is required");
        }
        requireMachineName(request.getTopic(), "topic");
        requireMachineName(request.getType(), "type");

        String recipient = emptyToNull(request.getRecipientUserUuid());
        String privilege = emptyToNull(request.getRequiredPrivilege());
        if (recipient == null && privilege == null) {
            throw new ValidationException("A notification must target a user or require a privilege");
        }
        if (recipient != null && !UUID_PATTERN.matcher(recipient).matches()) {
            throw new ValidationException("Invalid notification recipient UUID");
        }
        if (privilege != null && privilege.length() > 255) {
            throw new ValidationException("Notification privilege is too long");
        }
        String locationUuid = emptyToNull(request.getScopeLocationUuid());
        if (locationUuid != null && !UUID_PATTERN.matcher(locationUuid).matches()) {
            throw new ValidationException("Invalid notification scope location UUID");
        }

        String payload = request.getPayloadJson();
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length
                > NotificationConstants.MAX_PAYLOAD_BYTES) {
            throw new ValidationException("Notification payload is missing or too large");
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            if (parsed == null || !parsed.isObject()) {
                throw new ValidationException("Notification payload must be a JSON object");
            }
        } catch (IOException exception) {
            throw new ValidationException("Notification payload must be valid JSON", exception);
        }
    }

    private Set<String> normalizeTopics(Set<String> topics) {
        if (topics == null || topics.isEmpty()
                || topics.size() > NotificationConstants.MAX_TOPICS_PER_SUBSCRIPTION) {
            throw new IllegalArgumentException("One to sixteen notification topics are required");
        }
        Set<String> normalized = new HashSet<String>();
        for (String topic : topics) {
            requireMachineName(topic, "topic");
            normalized.add(topic);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private void requireMachineName(String value, String field) {
        if (value == null || !MACHINE_NAME.matcher(value).matches()) {
            throw new ValidationException("Invalid notification " + field);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private void retain(NotificationEvent event) {
        history.addLast(event);
        retainedPayloadBytes += event.getPayloadJson().getBytes(StandardCharsets.UTF_8).length;
        pruneExpiredHistory(event.getCreatedAtEpochMillis());
        while (history.size() > NotificationConstants.MAX_REPLAY_EVENTS
                || retainedPayloadBytes > NotificationConstants.MAX_REPLAY_PAYLOAD_BYTES) {
            removeOldestHistoryEvent();
        }
    }

    private void pruneExpiredHistory(long now) {
        while (!history.isEmpty()
                && now - history.peekFirst().getCreatedAtEpochMillis()
                        > NotificationConstants.REPLAY_WINDOW_MILLIS) {
            removeOldestHistoryEvent();
        }
    }

    private void removeOldestHistoryEvent() {
        NotificationEvent removed = history.pollFirst();
        if (removed != null) {
            retainedPayloadBytes -= removed.getPayloadJson().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private boolean replay(Subscriber subscriber, String lastEventId) {
        if (lastEventId == null) {
            return true;
        }
        pruneExpiredHistory(clock.currentTimeMillis());
        boolean cursorFound = false;
        List<NotificationEvent> replayEvents = new ArrayList<NotificationEvent>();
        for (NotificationEvent event : history) {
            if (!cursorFound) {
                cursorFound = lastEventId.equals(event.getId());
                continue;
            }
            if (subscriber.accepts(event)) {
                replayEvents.add(event);
                if (replayEvents.size() > NotificationConstants.MAX_PENDING_EVENTS_PER_CONNECTION) {
                    replayMissCount.incrementAndGet();
                    return false;
                }
            }
        }
        if (!cursorFound) {
            replayMissCount.incrementAndGet();
            return false;
        }
        for (NotificationEvent event : replayEvents) {
            try {
                subscriber.listener.onNotification(event);
                replayDeliveryCount.incrementAndGet();
            } catch (RuntimeException exception) {
                deliveryFailureCount.incrementAndGet();
                throw new IllegalStateException("Unable to replay a notification", exception);
            }
        }
        return true;
    }

    private void resetHistoryAndMetrics() {
        history.clear();
        retainedPayloadBytes = 0;
        publishedEventCount.set(0L);
        liveDeliveryCount.set(0L);
        replayDeliveryCount.set(0L);
        deliveryFailureCount.set(0L);
        replayMissCount.set(0L);
    }

    public void setClock(NotificationClock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Notification clock is required");
        }
        this.clock = clock;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Notification object mapper is required");
        }
        this.objectMapper = objectMapper;
    }

    private final class InMemorySubscription implements NotificationSubscription {

        private final String id;

        private final Subscriber subscriber;

        private final boolean replayComplete;

        private InMemorySubscription(String id, Subscriber subscriber, boolean replayComplete) {
            this.id = id;
            this.subscriber = subscriber;
            this.replayComplete = replayComplete;
        }

        @Override
        public void close() {
            subscribers.remove(id, subscriber);
        }

        @Override
        public boolean isReplayComplete() {
            return replayComplete;
        }
    }

    private static final class Subscriber {

        private final SubscriberIdentity identity;

        private final Set<String> topics;

        private final NotificationListener listener;

        private Subscriber(SubscriberIdentity identity, Set<String> topics,
                NotificationListener listener) {
            this.identity = identity;
            this.topics = topics;
            this.listener = listener;
        }

        private boolean accepts(NotificationEvent event) {
            if (!topics.contains(event.getTopic())) {
                return false;
            }
            if (event.getRecipientUserUuid() != null
                    && !event.getRecipientUserUuid().equals(identity.getUserUuid())) {
                return false;
            }
            if (event.getScopeLocationUuid() != null
                    && !event.getScopeLocationUuid().equals(identity.getLocationUuid())) {
                return false;
            }
            return event.getRequiredPrivilege() == null
                    || identity.hasPrivilege(event.getRequiredPrivilege());
        }
    }
}
