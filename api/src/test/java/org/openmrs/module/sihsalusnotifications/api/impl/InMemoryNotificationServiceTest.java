package org.openmrs.module.sihsalusnotifications.api.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.api.ValidationException;
import org.openmrs.module.sihsalusnotifications.NotificationConstants;
import org.openmrs.module.sihsalusnotifications.api.NotificationClock;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;
import org.openmrs.module.sihsalusnotifications.api.NotificationListener;
import org.openmrs.module.sihsalusnotifications.api.NotificationRequest;
import org.openmrs.module.sihsalusnotifications.api.NotificationSubscription;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class InMemoryNotificationServiceTest {

    private static final String USER_A = "11111111-1111-4111-8111-111111111111";

    private static final String USER_B = "22222222-2222-4222-8222-222222222222";

    private InMemoryNotificationService service;

    @Before
    public void setUp() {
        service = new InMemoryNotificationService();
        service.setClock(new NotificationClock() {
            @Override
            public long currentTimeMillis() {
                return 1_788_304_400_000L;
            }
        });
    }

    @Test
    public void targetedEventsReachOnlyTheAddressedUserOnTheSelectedTopic() {
        RecordingListener userA = new RecordingListener();
        RecordingListener userB = new RecordingListener();
        service.subscribe(identity(USER_A), singleton("queue"), userA);
        service.subscribe(identity(USER_B), singleton("queue"), userB);
        service.subscribe(identity(USER_A), singleton("system"), new RecordingListener());

        NotificationEvent event = service.publish(NotificationRequest.forUser(
                USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{\"queueEntryUuid\":\"abc\"}"));

        assertEquals(1, userA.events.size());
        assertTrue(userB.events.isEmpty());
        assertEquals(event, userA.events.get(0));
        assertEquals(1_788_304_400_000L, event.getCreatedAtEpochMillis());
        assertEquals(USER_A, event.getRecipientUserUuid());
    }

    @Test
    public void privilegeEventsReachOnlySubscribersWithTheRequiredPrivilege() {
        RecordingListener permitted = new RecordingListener();
        RecordingListener denied = new RecordingListener();
        service.subscribe(new SubscriberIdentity(USER_A, singleton("View Queue")),
                singleton("queue"), permitted);
        service.subscribe(identity(USER_B), singleton("queue"), denied);

        service.publish(NotificationRequest.forPrivilege("queue", "QUEUE_REFRESH",
                "{\"reason\":\"assignment\"}", "View Queue"));

        assertEquals(1, permitted.events.size());
        assertTrue(denied.events.isEmpty());
    }

    @Test
    public void superUsersReceivePrivilegeProtectedEvents() {
        RecordingListener listener = new RecordingListener();
        service.subscribe(new SubscriberIdentity(USER_A, Collections.<String>emptySet(), true),
                singleton("system"), listener);

        service.publish(NotificationRequest.forPrivilege("system", "MAINTENANCE_NOTICE",
                "{\"active\":true}", "A privilege introduced by another module"));

        assertEquals(1, listener.events.size());
    }

    @Test
    public void unrestrictedBroadcastsAreRejected() {
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forPrivilege("system", "NOTICE", "{}", null)));
    }

    @Test
    public void malformedNonObjectAndOversizedPayloadsAreRejected() {
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forUser(USER_A, "system", "NOTICE", "not-json")));
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forUser(USER_A, "system", "NOTICE", "[]")));

        char[] chars = new char[NotificationConstants.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(chars, 'a');
        String oversized = "{\"value\":\"" + new String(chars) + "\"}";
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forUser(USER_A, "system", "NOTICE", oversized)));
    }

    @Test
    public void machineNamesCannotInjectSseFields() {
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forUser(USER_A, "system\nevent:leak", "NOTICE", "{}")));
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forUser(USER_A, "system", "NOTICE\ndata:leak", "{}")));
    }

    @Test
    public void subscriptionsAreBoundedAndValidateTopics() {
        assertThrows(IllegalArgumentException.class, () -> service.subscribe(
                identity(USER_A), Collections.<String>emptySet(), new RecordingListener()));

        Set<String> tooMany = new HashSet<String>();
        for (int index = 0; index <= NotificationConstants.MAX_TOPICS_PER_SUBSCRIPTION; index++) {
            tooMany.add("topic-" + index);
        }
        assertThrows(IllegalArgumentException.class, () -> service.subscribe(
                identity(USER_A), tooMany, new RecordingListener()));

        for (int index = 0; index < NotificationConstants.MAX_SUBSCRIBERS; index++) {
            service.subscribe(identity(USER_A), singleton("system"), new RecordingListener());
        }
        assertThrows(IllegalStateException.class, () -> service.subscribe(
                identity(USER_A), singleton("system"), new RecordingListener()));
    }

    @Test
    public void closingAndFailingSubscriptionsReleaseCapacity() {
        NotificationSubscription subscription = service.subscribe(identity(USER_A),
                singleton("system"), new RecordingListener());
        assertEquals(1, service.getSubscriberCount());
        subscription.close();
        subscription.close();
        assertEquals(0, service.getSubscriberCount());

        service.subscribe(identity(USER_A), singleton("system"), new NotificationListener() {
            @Override
            public void onNotification(NotificationEvent event) {
                throw new IllegalStateException("client failed");
            }
        });
        service.publish(NotificationRequest.forUser(USER_A, "system", "NOTICE", "{}"));
        assertEquals(0, service.getSubscriberCount());
    }

    private SubscriberIdentity identity(String uuid) {
        return new SubscriberIdentity(uuid, Collections.<String>emptySet());
    }

    private Set<String> singleton(String value) {
        return Collections.singleton(value);
    }

    private static final class RecordingListener implements NotificationListener {

        private final List<NotificationEvent> events = new ArrayList<NotificationEvent>();

        @Override
        public void onNotification(NotificationEvent event) {
            events.add(event);
        }
    }
}
