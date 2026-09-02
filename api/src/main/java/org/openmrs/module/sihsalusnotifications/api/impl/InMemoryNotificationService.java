package org.openmrs.module.sihsalusnotifications.api.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.ValidationException;
import org.openmrs.module.sihsalusnotifications.NotificationConstants;
import org.openmrs.module.sihsalusnotifications.api.NotificationClock;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;
import org.openmrs.module.sihsalusnotifications.api.NotificationListener;
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

    private NotificationClock clock = new SystemNotificationClock();

    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onStartup() {
        subscribers.clear();
    }

    @Override
    public void onShutdown() {
        subscribers.clear();
    }

    @Override
    public NotificationEvent publish(NotificationRequest request) {
        validate(request);
        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID().toString(),
                request.getTopic(),
                request.getType(),
                request.getPayloadJson(),
                emptyToNull(request.getRecipientUserUuid()),
                emptyToNull(request.getRequiredPrivilege()),
                clock.currentTimeMillis());

        for (Map.Entry<String, Subscriber> entry : subscribers.entrySet()) {
            Subscriber subscriber = entry.getValue();
            if (!subscriber.accepts(event)) {
                continue;
            }
            try {
                subscriber.listener.onNotification(event);
            } catch (RuntimeException exception) {
                subscribers.remove(entry.getKey(), subscriber);
                log.warn("Removing a realtime notification subscriber after delivery failure");
            }
        }
        return event;
    }

    @Override
    public synchronized NotificationSubscription subscribe(SubscriberIdentity identity, Set<String> topics,
            NotificationListener listener) {
        if (identity == null || listener == null) {
            throw new IllegalArgumentException("Subscriber identity and listener are required");
        }
        Set<String> normalizedTopics = normalizeTopics(topics);
        if (subscribers.size() >= NotificationConstants.MAX_SUBSCRIBERS) {
            throw new IllegalStateException("Realtime subscriber capacity reached");
        }

        String id = UUID.randomUUID().toString();
        Subscriber subscriber = new Subscriber(identity, normalizedTopics, listener);
        subscribers.put(id, subscriber);
        return new InMemorySubscription(id, subscriber);
    }

    @Override
    public int getSubscriberCount() {
        return subscribers.size();
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

        private InMemorySubscription(String id, Subscriber subscriber) {
            this.id = id;
            this.subscriber = subscriber;
        }

        @Override
        public void close() {
            subscribers.remove(id, subscriber);
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
            return event.getRequiredPrivilege() == null
                    || identity.hasPrivilege(event.getRequiredPrivilege());
        }
    }
}
