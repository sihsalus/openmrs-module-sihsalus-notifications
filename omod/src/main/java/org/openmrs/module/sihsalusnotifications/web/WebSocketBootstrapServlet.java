package org.openmrs.module.sihsalusnotifications.web;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.openmrs.api.context.Context;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;

public class WebSocketBootstrapServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    public void init() throws ServletException {
        super.init();
        Object value = getServletContext().getAttribute(ServerContainer.class.getName());
        if (!(value instanceof ServerContainer)) {
            throw new ServletException("The servlet container does not provide javax.websocket");
        }

        NotificationRuntimeSettings settings = new NotificationRuntimeSettings();
        NotificationService service = Context.getService(NotificationService.class);
        NotificationWebSocketEndpoint.install(service, new NotificationJsonWriter(),
                settings.getWebSocketConnectionMillis());

        ServerEndpointConfig endpoint = ServerEndpointConfig.Builder
                .create(NotificationWebSocketEndpoint.class, NotificationWebSocketEndpoint.ENDPOINT_PATH)
                .configurator(new OpenmrsHandshakeConfigurator(
                        new WebSocketOriginPolicy(settings.getAdditionalAllowedOrigins())))
                .build();
        try {
            ((ServerContainer) value).addEndpoint(endpoint);
        } catch (DeploymentException exception) {
            NotificationWebSocketEndpoint.shutdown();
            throw new ServletException("Unable to register the SIHSALUS notification WebSocket", exception);
        }
    }

    @Override
    public void destroy() {
        NotificationWebSocketEndpoint.shutdown();
        super.destroy();
    }
}
