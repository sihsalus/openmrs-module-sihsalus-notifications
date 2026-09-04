package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.UUID;

import org.junit.After;
import org.junit.Test;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class WebSocketConnectionContextsTest {

    @After
    public void tearDown() {
        WebSocketConnectionContexts.clear();
    }

    @Test
    public void issuesOneUseTicketsBoundToTheAuthenticatedHttpSession() {
        SubscriberIdentity identity = identity();
        String connectionId = WebSocketConnectionContexts.issue(identity, "session-a", 1_000L);

        assertNotNull(connectionId);
        assertFalse(WebSocketConnectionContexts.bindHandshake(
                connectionId, "session-b", true, 1_001L));
        assertTrue(WebSocketConnectionContexts.bindHandshake(
                connectionId, "session-a", true, 1_002L));
        assertFalse(WebSocketConnectionContexts.bindHandshake(
                connectionId, "session-a", true, 1_003L));

        WebSocketConnectionContext context =
                WebSocketConnectionContexts.claim(connectionId, 1_004L);
        assertNotNull(context);
        assertSame(identity, context.getIdentity());
        assertTrue(context.isHandshakeBound());
        assertTrue(context.isOriginAllowed());
        assertNull(WebSocketConnectionContexts.claim(connectionId, 1_005L));
    }

    @Test
    public void rejectsExpiredAndNeverIssuedTickets() {
        String connectionId = WebSocketConnectionContexts.issue(identity(), "session-a", 1_000L);
        assertNotNull(connectionId);

        assertFalse(WebSocketConnectionContexts.bindHandshake(
                connectionId, "session-a", true, 61_001L));
        assertNull(WebSocketConnectionContexts.claim(connectionId, 61_002L));
        assertFalse(WebSocketConnectionContexts.bindHandshake(
                UUID.randomUUID().toString(), "session-a", true, 61_003L));
    }

    @Test
    public void boundsUnusedTicketsPerAuthenticatedSession() {
        for (int index = 0; index < 8; index++) {
            assertNotNull(WebSocketConnectionContexts.issue(
                    identity(), "session-a", 1_000L + index));
        }
        assertNull(WebSocketConnectionContexts.issue(identity(), "session-a", 1_009L));
        assertNotNull(WebSocketConnectionContexts.issue(identity(), "session-b", 1_010L));
    }

    private SubscriberIdentity identity() {
        return new SubscriberIdentity("11111111-1111-4111-8111-111111111111",
                Collections.singleton("app:home.laboratorio"), false,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }
}
