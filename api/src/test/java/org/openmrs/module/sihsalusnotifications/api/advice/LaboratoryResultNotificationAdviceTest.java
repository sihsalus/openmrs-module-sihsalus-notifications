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
import org.openmrs.Order;
import org.openmrs.TestOrder;
import org.openmrs.api.OrderService;
import org.openmrs.module.sihsalusnotifications.api.NotificationRequest;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class LaboratoryResultNotificationAdviceTest {

    private static final String ORDER_UUID = "5eb7c2ad-86ac-4f5e-8b86-ec14a0fb40df";

    private NotificationService notificationService;

    private LaboratoryResultNotificationAdvice advice;

    @Before
    public void setUp() {
        notificationService = mock(NotificationService.class);
        advice = new LaboratoryResultNotificationAdvice();
        advice.setNotificationService(notificationService);
        advice.setObjectMapper(new ObjectMapper());
    }

    @After
    public void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    public void publishesMinimalPrivilegeProtectedEventForCompletedTestOrder() throws Throwable {
        TestOrder order = completedTestOrder();
        MethodInvocation invocation = invocationFor(order, Order.FulfillerStatus.COMPLETED);

        Object result = advice.invoke(invocation);

        assertSame(order, result);
        ArgumentCaptor<NotificationRequest> requestCaptor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).publish(requestCaptor.capture());
        NotificationRequest request = requestCaptor.getValue();
        assertEquals(LaboratoryResultNotificationAdvice.TOPIC, request.getTopic());
        assertEquals(LaboratoryResultNotificationAdvice.EVENT_TYPE, request.getType());
        assertEquals(LaboratoryResultNotificationAdvice.REQUIRED_PRIVILEGE, request.getRequiredPrivilege());
        assertEquals(null, request.getRecipientUserUuid());
        assertEquals("{\"orderUuid\":\"" + ORDER_UUID + "\"}", request.getPayloadJson());
    }

    @Test
    public void waitsForTransactionCommitBeforePublishing() throws Throwable {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        advice.invoke(invocationFor(completedTestOrder(), Order.FulfillerStatus.COMPLETED));

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

        advice.invoke(invocationFor(completedTestOrder(), Order.FulfillerStatus.COMPLETED));
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(notificationService, never()).publish(any(NotificationRequest.class));
    }

    @Test
    public void ignoresNonCompletedAndNonLaboratoryOrders() throws Throwable {
        TestOrder inProgressOrder = new TestOrder();
        inProgressOrder.setUuid(ORDER_UUID);
        inProgressOrder.setFulfillerStatus(Order.FulfillerStatus.IN_PROGRESS);
        advice.invoke(invocationFor(inProgressOrder, Order.FulfillerStatus.IN_PROGRESS));

        Order genericOrder = new Order();
        genericOrder.setUuid(ORDER_UUID);
        genericOrder.setFulfillerStatus(Order.FulfillerStatus.COMPLETED);
        advice.invoke(invocationFor(genericOrder, Order.FulfillerStatus.COMPLETED));

        verify(notificationService, never()).publish(any(NotificationRequest.class));
    }

    @Test
    public void realtimeFailureDoesNotFailClinicalUpdate() throws Throwable {
        TestOrder order = completedTestOrder();
        when(notificationService.publish(any(NotificationRequest.class))).thenThrow(new IllegalStateException("full"));

        Object result = advice.invoke(invocationFor(order, Order.FulfillerStatus.COMPLETED));

        assertSame(order, result);
    }

    private TestOrder completedTestOrder() {
        TestOrder order = new TestOrder();
        order.setUuid(ORDER_UUID);
        order.setFulfillerStatus(Order.FulfillerStatus.COMPLETED);
        return order;
    }

    private MethodInvocation invocationFor(Order result, Order.FulfillerStatus status) throws Throwable {
        Method method = OrderService.class.getMethod(
                "updateOrderFulfillerStatus", Order.class, Order.FulfillerStatus.class, String.class);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getArguments()).thenReturn(new Object[] { result, status, null });
        when(invocation.proceed()).thenReturn(result);
        return invocation;
    }
}
