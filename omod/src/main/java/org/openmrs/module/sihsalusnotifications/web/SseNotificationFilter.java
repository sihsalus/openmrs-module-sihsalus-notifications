package org.openmrs.module.sihsalusnotifications.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmrs.api.context.Context;
import org.openmrs.module.sihsalusnotifications.NotificationConstants;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;
import org.openmrs.module.sihsalusnotifications.api.NotificationListener;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.openmrs.module.sihsalusnotifications.api.NotificationSubscription;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class SseNotificationFilter implements Filter {

    static final String RESYNC_EVENT_TYPE = "SIHSALUS_RESYNC_REQUIRED";

    private static final AtomicInteger activeConnections = new AtomicInteger();

    private final AuthenticatedSessionResolver sessionResolver = new AuthenticatedSessionResolver();

    private final TopicParser topicParser = new TopicParser();

    private final NotificationJsonWriter jsonWriter = new NotificationJsonWriter();

    private final NotificationRuntimeSettings settings = new NotificationRuntimeSettings();

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        if (!"GET".equals(httpRequest.getMethod())) {
            noStore(httpResponse);
            httpResponse.setHeader("Allow", "GET");
            httpResponse.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        SubscriberIdentity identity = sessionResolver.resolve(httpRequest.getSession(false));
        if (identity == null) {
            noStore(httpResponse);
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        final Set<String> topics;
        try {
            topics = topicParser.parse(httpRequest.getParameterValues("topics"));
        } catch (IllegalArgumentException exception) {
            noStore(httpResponse);
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
            return;
        }

        final String lastEventId;
        try {
            lastEventId = lastEventId(httpRequest);
        } catch (IllegalArgumentException exception) {
            noStore(httpResponse);
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, exception.getMessage());
            return;
        }

        NotificationService service = Context.getService(NotificationService.class);
        final ArrayBlockingQueue<NotificationEvent> pending =
                new ArrayBlockingQueue<NotificationEvent>(
                        NotificationConstants.MAX_PENDING_EVENTS_PER_CONNECTION);
        NotificationListener listener = new NotificationListener() {
            @Override
            public void onNotification(NotificationEvent event) {
                if (!pending.offer(event)) {
                    pending.poll();
                    pending.offer(event);
                }
            }
        };

        if (activeConnections.incrementAndGet() > NotificationConstants.MAX_CONCURRENT_SSE_CONNECTIONS) {
            activeConnections.decrementAndGet();
            noStore(httpResponse);
            httpResponse.setHeader("Retry-After", "5");
            httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        NotificationSubscription subscription = null;
        try {
            subscription = service.subscribe(identity, topics, listener, lastEventId);
            stream(httpResponse, pending, settings.getSseConnectionSeconds(),
                    subscription.isReplayComplete());
        } catch (IllegalStateException exception) {
            if (!httpResponse.isCommitted()) {
                noStore(httpResponse);
                httpResponse.setHeader("Retry-After", "5");
                httpResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            activeConnections.decrementAndGet();
        }
    }

    private void stream(HttpServletResponse response, ArrayBlockingQueue<NotificationEvent> pending,
            int connectionSeconds, boolean replayComplete) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream");
        noStore(response);
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        writePreamble(writer, replayComplete);
        writer.flush();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(connectionSeconds);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            long waitNanos = Math.min(remaining, TimeUnit.SECONDS.toNanos(10));
            NotificationEvent event;
            try {
                event = pending.poll(Math.max(1L, waitNanos), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                writer.write(": heartbeat\n\n");
            } else {
                writer.write("id: ");
                writer.write(event.getId());
                writer.write('\n');
                writer.write("event: ");
                writer.write(event.getType());
                writer.write('\n');
                writer.write("data: ");
                writer.write(jsonWriter.write(event));
                writer.write("\n\n");
            }
            writer.flush();
            if (writer.checkError()) {
                break;
            }
        }
    }

    void writePreamble(PrintWriter writer, boolean replayComplete) {
        writer.write("retry: 3000\n");
        writer.write(": connected\n\n");
        if (!replayComplete) {
            writer.write("id:\n");
            writer.write("event: ");
            writer.write(RESYNC_EVENT_TYPE);
            writer.write("\n");
            writer.write("data: {\"reason\":\"cursor-unavailable\"}\n\n");
        }
    }

    String lastEventId(HttpServletRequest request) {
        String value = request.getHeader("Last-Event-ID");
        if (value == null || value.trim().isEmpty()) {
            value = request.getParameter("after");
        }
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        try {
            if (!UUID.fromString(normalized).toString().equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Last notification event ID must be a UUID");
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
