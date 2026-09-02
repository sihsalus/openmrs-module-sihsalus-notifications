package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.Test;
import org.openmrs.Privilege;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.NotificationMetrics;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;

public class NotificationStatusFilterTest {

    @Test
    public void rejectsAnonymousNonAdministrativeAndNonGetRequests() throws Exception {
        NotificationService service = mock(NotificationService.class);
        NotificationStatusFilter filter = new NotificationStatusFilter(service);
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest post = mock(HttpServletRequest.class);
        HttpServletResponse postResponse = mock(HttpServletResponse.class);
        when(post.getMethod()).thenReturn("POST");
        filter.doFilter(post, postResponse, chain);
        verify(postResponse).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        verify(postResponse).setHeader("Allow", "GET");

        HttpServletRequest anonymous = mock(HttpServletRequest.class);
        HttpServletResponse anonymousResponse = mock(HttpServletResponse.class);
        when(anonymous.getMethod()).thenReturn("GET");
        filter.doFilter(anonymous, anonymousResponse, chain);
        verify(anonymousResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);

        HttpServletRequest denied = requestFor(Collections.<Privilege>emptySet());
        HttpServletResponse deniedResponse = mock(HttpServletResponse.class);
        filter.doFilter(denied, deniedResponse, chain);
        verify(deniedResponse).sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    public void returnsOnlyAggregateMetricsToAdministrators() throws Exception {
        NotificationService service = mock(NotificationService.class);
        when(service.getMetrics()).thenReturn(new NotificationMetrics(2, 3, 5L, 7L, 11L, 13L, 17L));
        NotificationStatusFilter filter = new NotificationStatusFilter(service);
        Privilege privilege = mock(Privilege.class);
        when(privilege.getPrivilege()).thenReturn(NotificationStatusFilter.REQUIRED_PRIVILEGE);
        HttpServletRequest request = requestFor(Collections.singleton(privilege));
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, mock(FilterChain.class));

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/json");
        verify(response).setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        assertEquals(
                "{\"subscriberCount\":2,\"retainedEventCount\":3,\"publishedEventCount\":5,"
                        + "\"liveDeliveryCount\":7,\"replayDeliveryCount\":11,"
                        + "\"deliveryFailureCount\":13,\"replayMissCount\":17}",
                body.toString());
        assertTrue(!body.toString().contains("uuid"));
    }

    private HttpServletRequest requestFor(java.util.Collection<Privilege> privileges) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        UserContext userContext = mock(UserContext.class);
        User user = mock(User.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE))
                .thenReturn(userContext);
        when(userContext.getAuthenticatedUser()).thenReturn(user);
        when(user.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(user.getRetired()).thenReturn(false);
        when(user.getPrivileges()).thenReturn(privileges);
        return request;
    }
}
