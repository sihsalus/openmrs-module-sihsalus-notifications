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

    private static final String LOCATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private static final String LOCATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private InMemoryNotificationService service;

    private MutableClock clock;

    @Before
    public void setUp() {
        service = new InMemoryNotificationService();
        clock = new MutableClock(1_788_304_400_000L);
        service.setClock(clock);
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
    public void locationScopedEventsRequireBothPrivilegeAndMatchingSessionLocation() {
        RecordingListener matching = new RecordingListener();
        RecordingListener wrongLocation = new RecordingListener();
        RecordingListener missingLocation = new RecordingListener();
        service.subscribe(identityAt(USER_A, LOCATION_A, "View Queue"), singleton("queue"), matching);
        service.subscribe(identityAt(USER_B, LOCATION_B, "View Queue"), singleton("queue"), wrongLocation);
        service.subscribe(new SubscriberIdentity(USER_B, singleton("View Queue")),
                singleton("queue"), missingLocation);

        service.publish(NotificationRequest.forPrivilegeAtLocation("queue", "QUEUE_REFRESH",
                "{\"reason\":\"assignment\"}", "View Queue", LOCATION_A));

        assertEquals(1, matching.events.size());
        assertTrue(wrongLocation.events.isEmpty());
        assertTrue(missingLocation.events.isEmpty());
    }

    @Test
    public void locationScopeAlsoAppliesToSuperUsers() {
        RecordingListener listener = new RecordingListener();
        service.subscribe(new SubscriberIdentity(USER_A, Collections.<String>emptySet(), true, LOCATION_B),
                singleton("queue"), listener);

        service.publish(NotificationRequest.forPrivilegeAtLocation("queue", "QUEUE_REFRESH",
                "{}", "View Queue", LOCATION_A));

        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void replaysAuthorizedEventsAfterAKnownCursor() {
        NotificationEvent first = service.publish(NotificationRequest.forUser(
                USER_A, "queue", "QUEUE_ENTRY_CREATED", "{}"));
        NotificationEvent second = service.publish(NotificationRequest.forUser(
                USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        service.publish(NotificationRequest.forUser(USER_B, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        RecordingListener listener = new RecordingListener();

        NotificationSubscription subscription = service.subscribe(
                identity(USER_A), singleton("queue"), listener, first.getId());

        assertTrue(subscription.isReplayComplete());
        assertEquals(Collections.singletonList(second), listener.events);
        assertEquals(1L, service.getMetrics().getReplayDeliveryCount());
    }

    @Test
    public void reportsAnUnavailableReplayCursorWithoutLeakingHistory() {
        service.publish(NotificationRequest.forUser(USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        RecordingListener listener = new RecordingListener();

        NotificationSubscription subscription = service.subscribe(identity(USER_A), singleton("queue"), listener,
                "33333333-3333-4333-8333-333333333333");

        assertTrue(!subscription.isReplayComplete());
        assertTrue(listener.events.isEmpty());
        assertEquals(1L, service.getMetrics().getReplayMissCount());
        assertThrows(IllegalArgumentException.class, () -> service.subscribe(
                identity(USER_A), singleton("queue"), listener, "not-a-uuid"));
    }

    @Test
    public void requestsResynchronizationInsteadOfOverflowingTheConnectionQueue() {
        NotificationEvent cursor = service.publish(NotificationRequest.forUser(
                USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        for (int index = 0; index <= NotificationConstants.MAX_PENDING_EVENTS_PER_CONNECTION; index++) {
            service.publish(NotificationRequest.forUser(
                    USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        }
        RecordingListener listener = new RecordingListener();

        NotificationSubscription subscription = service.subscribe(
                identity(USER_A), singleton("queue"), listener, cursor.getId());

        assertTrue(!subscription.isReplayComplete());
        assertTrue(listener.events.isEmpty());
        assertEquals(1L, service.getMetrics().getReplayMissCount());
    }

    @Test
    public void expiresReplayHistoryAndKeepsItBounded() {
        NotificationEvent expired = service.publish(NotificationRequest.forUser(
                USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        clock.advance(NotificationConstants.REPLAY_WINDOW_MILLIS + 1L);
        service.publish(NotificationRequest.forUser(USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));

        assertEquals(1, service.getMetrics().getRetainedEventCount());
        NotificationSubscription subscription = service.subscribe(identity(USER_A), singleton("queue"),
                new RecordingListener(), expired.getId());
        assertTrue(!subscription.isReplayComplete());

        for (int index = 0; index <= NotificationConstants.MAX_REPLAY_EVENTS; index++) {
            service.publish(NotificationRequest.forUser(USER_A, "queue", "QUEUE_ENTRY_UPDATED", "{}"));
        }
        assertEquals(NotificationConstants.MAX_REPLAY_EVENTS,
                service.getMetrics().getRetainedEventCount());
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
        assertThrows(ValidationException.class, () -> service.publish(
                NotificationRequest.forPrivilegeAtLocation("system", "NOTICE", "{}",
                        "View Queue", "not-a-uuid")));
        assertThrows(IllegalArgumentException.class, () -> NotificationRequest.forPrivilegeAtLocation(
                "system", "NOTICE", "{}", "View Queue", " "));
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
        assertEquals(1L, service.getMetrics().getDeliveryFailureCount());
    }

    private SubscriberIdentity identity(String uuid) {
        return new SubscriberIdentity(uuid, Collections.<String>emptySet());
    }

    private SubscriberIdentity identityAt(String uuid, String locationUuid, String privilege) {
        return new SubscriberIdentity(uuid, singleton(privilege), false, locationUuid);
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

    private static final class MutableClock implements NotificationClock {

        private long currentTimeMillis;

        private MutableClock(long currentTimeMillis) {
            this.currentTimeMillis = currentTimeMillis;
        }

        @Override
        public long currentTimeMillis() {
            return currentTimeMillis;
        }

        private void advance(long millis) {
            currentTimeMillis += millis;
        }
    }
}
