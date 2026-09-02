package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.websocket.CloseReason;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.Session;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.module.sihsalusnotifications.NotificationConstants;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;

public class WebSocketDeliveryTest {

    @Test
    public void disconnectsInsteadOfGrowingAnUnboundedSlowClientQueue() throws Exception {
        Session session = mock(Session.class);
        RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
        when(session.getAsyncRemote()).thenReturn(remote);
        when(session.isOpen()).thenReturn(true);
        WebSocketDelivery delivery = new WebSocketDelivery(session, new NotificationJsonWriter());

        delivery.onNotification(event("first"));
        for (int index = 0; index < NotificationConstants.MAX_PENDING_EVENTS_PER_CONNECTION; index++) {
            delivery.onNotification(event("queued-" + index));
        }
        delivery.onNotification(event("overflow"));

        verify(remote).sendText(anyString(), any(SendHandler.class));
        ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
        verify(session).close(reason.capture());
        assertEquals(1013, reason.getValue().getCloseCode().getCode());
    }

    private NotificationEvent event(String id) {
        return new NotificationEvent(id, "system", "NOTICE", "{}",
                "11111111-1111-4111-8111-111111111111", null, 1L);
    }
}
