package org.openmrs.module.sihsalusnotifications.api.advice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.DrugOrder;
import org.openmrs.Order;
import org.openmrs.TestOrder;
import org.openmrs.api.OrderContext;
import org.openmrs.api.OrderService;
import org.openmrs.module.sihsalusnotifications.api.NotificationRequest;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class OrderCreationNotificationAdviceTest {

    private static final String ORDER_UUID = "deac8f42-3b93-44ab-99dd-f6d37efb3259";

    private NotificationService notificationService;

    private OrderCreationNotificationAdvice advice;

    @Before
    public void setUp() {
        notificationService = mock(NotificationService.class);
        advice = new OrderCreationNotificationAdvice();
        advice.setNotificationService(notificationService);
        advice.setObjectMapper(new ObjectMapper());
    }

    @After
    public void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    public void publishesPrivilegeProtectedEventForNewMedicationOrder() throws Throwable {
        DrugOrder order = newOrder(new DrugOrder());

        Object result = advice.invoke(invocationFor(order, "saveOrder"));

        assertSame(order, result);
        assertPublished(
                OrderCreationNotificationAdvice.PHARMACY_TOPIC,
                OrderCreationNotificationAdvice.PHARMACY_EVENT_TYPE,
                OrderCreationNotificationAdvice.PHARMACY_PRIVILEGE);
    }

    @Test
    public void publishesPrivilegeProtectedEventForNewLaboratoryOrder() throws Throwable {
        TestOrder order = newOrder(new TestOrder());

        advice.invoke(invocationFor(order, "saveRetrospectiveOrder"));

        assertPublished(
                OrderCreationNotificationAdvice.LABORATORY_TOPIC,
                OrderCreationNotificationAdvice.LABORATORY_EVENT_TYPE,
                OrderCreationNotificationAdvice.LABORATORY_PRIVILEGE);
    }

    @Test
    public void waitsForTransactionCommitBeforePublishing() throws Throwable {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        advice.invoke(invocationFor(newOrder(new DrugOrder()), "saveOrder"));

        verify(notificationService, never()).publish(any(NotificationRequest.class));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());

        synchronizations.get(0).afterCommit();

        verify(notificationService).publish(any(NotificationRequest.class));
    }

    @Test
    public void doesNotPublishWhenTransactionRollsBack() throws Throwable {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        advice.invoke(invocationFor(newOrder(new TestOrder()), "saveOrder"));
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(notificationService, never()).publish(any(NotificationRequest.class));
    }

    @Test
    public void ignoresRevisionsExistingOrdersAndUnsupportedOrderTypes() throws Throwable {
        DrugOrder revision = newOrder(new DrugOrder());
        revision.setAction(Order.Action.REVISE);
        advice.invoke(invocationFor(revision, "saveOrder"));

        TestOrder existing = newOrder(new TestOrder());
        existing.setOrderId(42);
        advice.invoke(invocationFor(existing, "saveOrder"));

        Order generic = newOrder(new Order());
        advice.invoke(invocationFor(generic, "saveOrder"));

        verify(notificationService, never()).publish(any(NotificationRequest.class));
    }

    @Test
    public void realtimeFailureDoesNotFailClinicalCreate() throws Throwable {
        DrugOrder order = newOrder(new DrugOrder());
        when(notificationService.publish(any(NotificationRequest.class))).thenThrow(new IllegalStateException("full"));

        Object result = advice.invoke(invocationFor(order, "saveOrder"));

        assertSame(order, result);
    }

    private void assertPublished(String topic, String eventType, String privilege) {
        ArgumentCaptor<NotificationRequest> requestCaptor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).publish(requestCaptor.capture());
        NotificationRequest request = requestCaptor.getValue();
        assertEquals(topic, request.getTopic());
        assertEquals(eventType, request.getType());
        assertEquals(privilege, request.getRequiredPrivilege());
        assertEquals(null, request.getRecipientUserUuid());
        assertEquals("{\"orderUuid\":\"" + ORDER_UUID + "\"}", request.getPayloadJson());
    }

    private <T extends Order> T newOrder(T order) {
        order.setUuid(ORDER_UUID);
        order.setAction(Order.Action.NEW);
        return order;
    }

    private MethodInvocation invocationFor(Order order, String methodName) throws Throwable {
        Method method = OrderService.class.getMethod(methodName, Order.class, OrderContext.class);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getArguments()).thenReturn(new Object[] { order, new OrderContext() });
        when(invocation.proceed()).thenReturn(order);
        return invocation;
    }
}
