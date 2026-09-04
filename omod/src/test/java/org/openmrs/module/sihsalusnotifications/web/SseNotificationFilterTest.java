package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

public class SseNotificationFilterTest {

    private static final String HEADER_CURSOR = "11111111-1111-4111-8111-111111111111";

    private static final String QUERY_CURSOR = "22222222-2222-4222-8222-222222222222";

    private final SseNotificationFilter filter = new SseNotificationFilter();

    @Test
    public void acceptsStandardHeaderAndQueryFallbackReplayCursors() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Last-Event-ID")).thenReturn(HEADER_CURSOR);
        when(request.getParameter("after")).thenReturn(QUERY_CURSOR);
        assertEquals(HEADER_CURSOR, filter.lastEventId(request));

        when(request.getHeader("Last-Event-ID")).thenReturn(null);
        assertEquals(QUERY_CURSOR, filter.lastEventId(request));

        when(request.getParameter("after")).thenReturn(null);
        assertEquals(null, filter.lastEventId(request));
    }

    @Test
    public void rejectsMalformedReplayCursors() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Last-Event-ID")).thenReturn("not-a-uuid\nevent: injected");

        assertThrows(IllegalArgumentException.class, () -> filter.lastEventId(request));
    }

    @Test
    public void asksClientsToResynchronizeOnlyWhenReplayIsIncomplete() {
        StringWriter completeBody = new StringWriter();
        filter.writePreamble(new PrintWriter(completeBody), true);
        assertTrue(!completeBody.toString().contains(SseNotificationFilter.RESYNC_EVENT_TYPE));

        StringWriter incompleteBody = new StringWriter();
        filter.writePreamble(new PrintWriter(incompleteBody), false);
        assertTrue(incompleteBody.toString().contains("id:\n"));
        assertTrue(incompleteBody.toString().contains("event: " + SseNotificationFilter.RESYNC_EVENT_TYPE));
        assertTrue(incompleteBody.toString().contains("cursor-unavailable"));
    }
}
