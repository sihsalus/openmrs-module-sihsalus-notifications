package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.openmrs.module.ModuleActivator;
import org.openmrs.module.sihsalusnotifications.SihsalusNotificationsActivator;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class TransportModuleConfigurationTest {

    @Test
    public void omodRegistersWebSocketBootstrapAndBothSsePathForms() throws Exception {
        try (InputStream config = getClass().getResourceAsStream("/config.xml")) {
            assertTrue("Packaged config.xml must be available", config != null);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config);
            String activatorClassName = document.getElementsByTagName("activator").item(0).getTextContent().trim();
            assertEquals(SihsalusNotificationsActivator.class.getName(), activatorClassName);
            assertTrue(ModuleActivator.class.isAssignableFrom(Class.forName(activatorClassName)));
            assertEquals(WebSocketBootstrapServlet.class.getName(),
                    document.getElementsByTagName("servlet-class").item(0).getTextContent().trim());
            assertEquals(SseNotificationFilter.class.getName(),
                    document.getElementsByTagName("filter-class").item(0).getTextContent().trim());

            NodeList patterns = document.getElementsByTagName("url-pattern");
            List<String> values = new ArrayList<String>();
            for (int index = 0; index < patterns.getLength(); index++) {
                values.add(patterns.item(index).getTextContent().trim());
            }
            assertTrue(values.contains("/ws/sihsalus/notifications/sse"));
            assertTrue(values.contains("/ws/sihsalus/notifications/sse/"));
        }
    }

    @Test
    public void sseEndpointRejectsAnonymousAndNonGetRequestsWithoutCaching() throws Exception {
        SseNotificationFilter filter = new SseNotificationFilter();
        HttpServletRequest anonymous = mock(HttpServletRequest.class);
        HttpServletResponse anonymousResponse = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(anonymous.getMethod()).thenReturn("GET");
        filter.doFilter(anonymous, anonymousResponse, chain);
        verify(anonymousResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verify(anonymousResponse).setHeader(eq("Cache-Control"), contains("no-store"));

        HttpServletRequest post = mock(HttpServletRequest.class);
        HttpServletResponse postResponse = mock(HttpServletResponse.class);
        when(post.getMethod()).thenReturn("POST");
        filter.doFilter(post, postResponse, chain);
        verify(postResponse).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        verify(postResponse).setHeader("Allow", "GET");
    }
}
