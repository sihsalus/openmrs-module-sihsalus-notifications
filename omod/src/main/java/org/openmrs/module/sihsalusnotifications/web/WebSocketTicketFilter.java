package org.openmrs.module.sihsalusnotifications.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class WebSocketTicketFilter implements Filter {

    private final AuthenticatedSessionResolver sessionResolver = new AuthenticatedSessionResolver();

    private final WebSocketOriginPolicy originPolicy;

    public WebSocketTicketFilter() {
        this(new WebSocketOriginPolicy(
                new NotificationRuntimeSettings().getAdditionalAllowedOrigins()));
    }

    WebSocketTicketFilter(WebSocketOriginPolicy originPolicy) {
        this.originPolicy = originPolicy;
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        noStore(httpResponse);
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");

        if (!"POST".equals(httpRequest.getMethod())) {
            httpResponse.setHeader("Allow", "POST");
            httpResponse.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (!originPolicy.isAllowed(requestUri(httpRequest), headers(httpRequest))) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        HttpSession httpSession = httpRequest.getSession(false);
        SubscriberIdentity identity = sessionResolver.resolve(httpSession);
        if (identity == null) {
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String connectionId;
        try {
            connectionId = WebSocketConnectionContexts.issue(identity, httpSession.getId());
        } catch (IllegalStateException exception) {
            connectionId = null;
        }
        if (connectionId == null) {
            httpResponse.setHeader("Retry-After", "5");
            httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setContentType("application/json");
        PrintWriter writer = httpResponse.getWriter();
        writer.write("{\"connectionId\":\"");
        writer.write(connectionId);
        writer.write("\"}");
        writer.flush();
    }

    private URI requestUri(HttpServletRequest request) {
        try {
            return new URI(request.getRequestURL().toString());
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        addHeader(headers, "Origin", request.getHeader("Origin"));
        addHeader(headers, "Host", request.getHeader("Host"));
        addHeader(headers, "X-Forwarded-Proto", request.getHeader("X-Forwarded-Proto"));
        return headers;
    }

    private void addHeader(Map<String, List<String>> headers, String name, String value) {
        if (value != null) {
            headers.put(name, Collections.singletonList(value));
        }
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }

    @Override
    public void destroy() {
    }
}
