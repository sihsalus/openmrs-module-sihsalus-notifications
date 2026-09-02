package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.NotificationListener;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.openmrs.module.sihsalusnotifications.api.NotificationSubscription;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class NotificationWebSocketEndpointTest {

    @After
    public void tearDown() {
        NotificationWebSocketEndpoint.shutdown();
        WebSocketConnectionContexts.clear();
    }

    @Test
    public void rejectsConnectionsWithoutAClaimedHandshakeContext() throws Exception {
        Session session = mock(Session.class);
        when(session.getRequestParameterMap()).thenReturn(Collections.<String, List<String>>emptyMap());

        new NotificationWebSocketEndpoint().onOpen(session, mock(EndpointConfig.class));

        verify(session).close(any(CloseReason.class));
    }

    @Test
    public void subscribesAnAuthenticatedConnectionWithItsRequestedTopics() throws Exception {
        String connectionId = UUID.randomUUID().toString();
        HttpSession httpSession = authenticatedHttpSession();
        assertTrue(WebSocketConnectionContexts.register(connectionId,
                new WebSocketConnectionContext(httpSession, true, System.currentTimeMillis())));

        Session session = mock(Session.class);
        Map<String, List<String>> parameters = new HashMap<String, List<String>>();
        parameters.put("connectionId", Collections.singletonList(connectionId));
        parameters.put("topics", Arrays.asList("queue,laboratory"));
        when(session.getRequestParameterMap()).thenReturn(parameters);
        when(session.getUserProperties()).thenReturn(new HashMap<String, Object>());

        NotificationService service = mock(NotificationService.class);
        NotificationSubscription subscription = mock(NotificationSubscription.class);
        when(service.subscribe(any(SubscriberIdentity.class), anySet(), any(NotificationListener.class)))
                .thenReturn(subscription);
        NotificationWebSocketEndpoint.install(service, new NotificationJsonWriter(), 60_000L);

        NotificationWebSocketEndpoint endpoint = new NotificationWebSocketEndpoint();
        endpoint.onOpen(session, mock(EndpointConfig.class));

        ArgumentCaptor<SubscriberIdentity> identity = ArgumentCaptor.forClass(SubscriberIdentity.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Set<String>> topics = ArgumentCaptor.forClass(java.util.Set.class);
        verify(service).subscribe(identity.capture(), topics.capture(), any(NotificationListener.class));
        assertEquals("11111111-1111-4111-8111-111111111111", identity.getValue().getUserUuid());
        assertTrue(topics.getValue().contains("queue"));
        assertTrue(topics.getValue().contains("laboratory"));
        verify(session).setMaxIdleTimeout(60_000L);

        endpoint.onClose(session, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "done"));
        verify(subscription).close();
    }

    private HttpSession authenticatedHttpSession() {
        HttpSession httpSession = mock(HttpSession.class);
        UserContext userContext = mock(UserContext.class);
        User user = mock(User.class);
        when(httpSession.getAttribute(AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE))
                .thenReturn(userContext);
        when(userContext.getAuthenticatedUser()).thenReturn(user);
        when(user.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(user.getRetired()).thenReturn(false);
        when(user.getPrivileges()).thenReturn(Collections.emptyList());
        return httpSession;
    }
}
