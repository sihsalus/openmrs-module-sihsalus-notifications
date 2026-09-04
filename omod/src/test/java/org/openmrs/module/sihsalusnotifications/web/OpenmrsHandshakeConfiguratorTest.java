package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpSession;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

import org.junit.After;
import org.junit.Test;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class OpenmrsHandshakeConfiguratorTest {

    @After
    public void tearDown() {
        WebSocketConnectionContexts.clear();
    }

    @Test
    public void bindsAnIssuedTicketFromTheStandardParameterMapWhenTheContainerUriOmitsTheQuery()
            throws Exception {
        SubscriberIdentity identity = identity();
        String connectionId = WebSocketConnectionContexts.issue(identity, "session-a");
        assertNotNull(connectionId);
        HttpSession httpSession = mock(HttpSession.class);
        when(httpSession.getId()).thenReturn("session-a");
        HandshakeRequest request = mock(HandshakeRequest.class);
        when(request.getRequestURI()).thenReturn(
                new URI("ws://backend:8080/openmrs/ws/sihsalus/notifications"));
        when(request.getParameterMap()).thenReturn(Collections.singletonMap(
                "connectionId", Collections.singletonList(connectionId)));
        when(request.getHeaders()).thenReturn(headers(
                "Origin", "https://sihsalus.example",
                "Host", "sihsalus.example",
                "X-Forwarded-Proto", "https"));
        when(request.getHttpSession()).thenReturn(httpSession);

        new OpenmrsHandshakeConfigurator(new WebSocketOriginPolicy(Collections.<String>emptySet()))
                .modifyHandshake(mock(ServerEndpointConfig.class), request, mock(HandshakeResponse.class));

        WebSocketConnectionContext context = WebSocketConnectionContexts.claim(connectionId);
        assertNotNull(context);
        assertSame(identity, context.getIdentity());
        assertTrue(context.isHandshakeBound());
        assertTrue(context.isOriginAllowed());
    }

    @Test
    public void doesNotBindClientGeneratedOrDifferentSessionTickets() throws Exception {
        String issued = WebSocketConnectionContexts.issue(identity(), "session-a");
        assertNotNull(issued);
        HttpSession wrongSession = mock(HttpSession.class);
        when(wrongSession.getId()).thenReturn("session-b");

        HandshakeRequest request = mock(HandshakeRequest.class);
        when(request.getRequestURI()).thenReturn(
                new URI("ws://backend:8080/openmrs/ws/sihsalus/notifications"));
        when(request.getParameterMap()).thenReturn(Collections.singletonMap(
                "connectionId", Collections.singletonList(issued)));
        when(request.getHeaders()).thenReturn(headers(
                "Origin", "https://sihsalus.example",
                "Host", "sihsalus.example",
                "X-Forwarded-Proto", "https"));
        when(request.getHttpSession()).thenReturn(wrongSession);

        OpenmrsHandshakeConfigurator configurator = new OpenmrsHandshakeConfigurator(
                new WebSocketOriginPolicy(Collections.<String>emptySet()));
        configurator.modifyHandshake(mock(ServerEndpointConfig.class), request,
                mock(HandshakeResponse.class));

        WebSocketConnectionContext context = WebSocketConnectionContexts.claim(issued);
        assertNotNull(context);
        assertFalse(context.isHandshakeBound());

        String arbitrary = UUID.randomUUID().toString();
        when(request.getParameterMap()).thenReturn(Collections.singletonMap(
                "connectionId", Collections.singletonList(arbitrary)));
        configurator.modifyHandshake(mock(ServerEndpointConfig.class), request,
                mock(HandshakeResponse.class));
        assertFalse(WebSocketConnectionContexts.bindHandshake(
                arbitrary, "session-b", true));
    }

    private SubscriberIdentity identity() {
        return new SubscriberIdentity("11111111-1111-4111-8111-111111111111",
                Collections.<String>emptySet(), false,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }

    private Map<String, List<String>> headers(String... pairs) {
        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        for (int index = 0; index < pairs.length; index += 2) {
            headers.put(pairs[index], Arrays.asList(pairs[index + 1]));
        }
        return headers;
    }
}
