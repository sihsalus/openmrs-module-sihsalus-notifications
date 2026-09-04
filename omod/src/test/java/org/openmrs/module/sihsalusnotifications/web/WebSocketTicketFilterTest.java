package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.UUID;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.After;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.LocationTag;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.FacilityLocationScope;

public class WebSocketTicketFilterTest {

    private final WebSocketTicketFilter filter = new WebSocketTicketFilter(
            new WebSocketOriginPolicy(Collections.<String>emptySet()));

    @After
    public void tearDown() {
        WebSocketConnectionContexts.clear();
    }

    @Test
    public void issuesANoStoreOneUseTicketForAnAuthenticatedSameOriginSession() throws Exception {
        HttpSession session = authenticatedSession("session-a");
        HttpServletRequest request = request("POST", "https://sihsalus.example", session);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, mock(FilterChain.class));

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/json");
        verify(response).setHeader(eq("Cache-Control"), contains("no-store"));
        String json = body.toString();
        assertTrue(json.startsWith("{\"connectionId\":\""));
        String connectionId = json.substring(17, json.length() - 2);
        assertEquals(connectionId, UUID.fromString(connectionId).toString());
        assertTrue(WebSocketConnectionContexts.bindHandshake(
                connectionId, "session-a", true));
        WebSocketConnectionContext context = WebSocketConnectionContexts.claim(connectionId);
        assertNotNull(context);
        assertEquals("11111111-1111-4111-8111-111111111111",
                context.getIdentity().getUserUuid());
    }

    @Test
    public void rejectsWrongOriginAnonymousAndNonPostRequests() throws Exception {
        HttpServletResponse wrongOriginResponse = mock(HttpServletResponse.class);
        filter.doFilter(request("POST", "https://attacker.example",
                authenticatedSession("session-a")), wrongOriginResponse, mock(FilterChain.class));
        verify(wrongOriginResponse).sendError(HttpServletResponse.SC_FORBIDDEN);

        HttpServletResponse anonymousResponse = mock(HttpServletResponse.class);
        filter.doFilter(request("POST", "https://sihsalus.example", null),
                anonymousResponse, mock(FilterChain.class));
        verify(anonymousResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);

        HttpServletResponse getResponse = mock(HttpServletResponse.class);
        filter.doFilter(request("GET", "https://sihsalus.example", null),
                getResponse, mock(FilterChain.class));
        verify(getResponse).setHeader("Allow", "POST");
        verify(getResponse).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    private HttpServletRequest request(String method, String origin, HttpSession session) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getHeader("Host")).thenReturn("sihsalus.example");
        when(request.getRequestURL()).thenReturn(new StringBuffer(
                "https://sihsalus.example/openmrs/ws/sihsalus/notifications/websocket-ticket"));
        when(request.getSession(false)).thenReturn(session);
        return request;
    }

    private HttpSession authenticatedSession(String sessionId) {
        HttpSession session = mock(HttpSession.class);
        UserContext userContext = mock(UserContext.class);
        User user = mock(User.class);
        Location location = new Location();
        location.setUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        location.addTag(new LocationTag(
                FacilityLocationScope.FACILITY_LOCATION_TAG, "Synthetic facility tag"));
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttribute(AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE))
                .thenReturn(userContext);
        when(userContext.getAuthenticatedUser()).thenReturn(user);
        when(user.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(user.getRetired()).thenReturn(false);
        when(user.getPrivileges()).thenReturn(Collections.emptyList());
        when(userContext.getLocation()).thenReturn(location);
        return session;
    }
}
