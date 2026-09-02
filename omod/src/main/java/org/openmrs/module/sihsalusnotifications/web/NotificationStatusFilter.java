package org.openmrs.module.sihsalusnotifications.web;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmrs.api.context.Context;
import org.openmrs.module.sihsalusnotifications.api.NotificationMetrics;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class NotificationStatusFilter implements Filter {

    static final String REQUIRED_PRIVILEGE = "View Administration Functions";

    private final AuthenticatedSessionResolver sessionResolver = new AuthenticatedSessionResolver();

    private final NotificationService injectedService;

    public NotificationStatusFilter() {
        this(null);
    }

    NotificationStatusFilter(NotificationService service) {
        this.injectedService = service;
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

        if (!"GET".equals(httpRequest.getMethod())) {
            httpResponse.setHeader("Allow", "GET");
            httpResponse.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        SubscriberIdentity identity = sessionResolver.resolve(httpRequest.getSession(false));
        if (identity == null) {
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!identity.hasPrivilege(REQUIRED_PRIVILEGE)) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        NotificationMetrics metrics = service().getMetrics();
        httpResponse.setStatus(HttpServletResponse.SC_OK);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setContentType("application/json");
        PrintWriter writer = httpResponse.getWriter();
        writer.write("{");
        writer.write("\"subscriberCount\":" + metrics.getSubscriberCount());
        writer.write(",\"retainedEventCount\":" + metrics.getRetainedEventCount());
        writer.write(",\"publishedEventCount\":" + metrics.getPublishedEventCount());
        writer.write(",\"liveDeliveryCount\":" + metrics.getLiveDeliveryCount());
        writer.write(",\"replayDeliveryCount\":" + metrics.getReplayDeliveryCount());
        writer.write(",\"deliveryFailureCount\":" + metrics.getDeliveryFailureCount());
        writer.write(",\"replayMissCount\":" + metrics.getReplayMissCount());
        writer.write("}");
        writer.flush();
    }

    private NotificationService service() {
        return injectedService == null
                ? Context.getService(NotificationService.class) : injectedService;
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
