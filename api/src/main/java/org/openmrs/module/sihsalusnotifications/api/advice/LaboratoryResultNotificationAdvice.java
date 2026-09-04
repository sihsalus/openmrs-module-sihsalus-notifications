package org.openmrs.module.sihsalusnotifications.api.advice;

import java.util.Collections;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Order;
import org.openmrs.TestOrder;
import org.openmrs.module.sihsalusnotifications.api.FacilityLocationScope;
import org.openmrs.module.sihsalusnotifications.api.NotificationRequest;
import org.openmrs.module.sihsalusnotifications.api.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes a privacy-minimized event after a laboratory order becomes available.
 *
 * <p>The advice is installed on {@code OrderService} by the module activator. It deliberately
 * waits for transaction commit and never lets a realtime delivery failure affect the clinical
 * order update.</p>
 */
public final class LaboratoryResultNotificationAdvice implements MethodInterceptor {

    public static final String TOPIC = "laboratory";

    public static final String EVENT_TYPE = "LAB_RESULT_READY";

    public static final String REQUIRED_PRIVILEGE = "app:home.laboratorio";

    private static final String UPDATE_METHOD = "updateOrderFulfillerStatus";

    private static final Logger log = LoggerFactory.getLogger(LaboratoryResultNotificationAdvice.class);

    private NotificationService notificationService;

    private ObjectMapper objectMapper;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        boolean completionTransition = isCompletionTransition(invocation);
        Object result = invocation.proceed();
        if (!completionTransition || !isCompletedLaboratoryOrder(result)) {
            return result;
        }

        Order order = (Order) result;
        final String orderUuid = order.getUuid();
        final String scopeLocationUuid = scopeLocationUuid(order);
        if (scopeLocationUuid == null) {
            log.warn("Skipping a laboratory result notification without a tagged facility scope");
            return result;
        }
        Runnable publish = new Runnable() {

            @Override
            public void run() {
                publishSafely(orderUuid, scopeLocationUuid);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
        return result;
    }

    private boolean isCompletionTransition(MethodInvocation invocation) {
        if (!UPDATE_METHOD.equals(invocation.getMethod().getName())) {
            return false;
        }

        Object[] arguments = invocation.getArguments();
        return arguments.length >= 2
                && arguments[0] instanceof TestOrder
                && arguments[1] == Order.FulfillerStatus.COMPLETED
                && ((Order) arguments[0]).getFulfillerStatus() != Order.FulfillerStatus.COMPLETED;
    }

    private boolean isCompletedLaboratoryOrder(Object result) {
        return result instanceof TestOrder
                && ((Order) result).getFulfillerStatus() == Order.FulfillerStatus.COMPLETED
                && ((Order) result).getUuid() != null
                && !((Order) result).getUuid().trim().isEmpty();
    }

    private String scopeLocationUuid(Order order) {
        if (order.getEncounter() == null || order.getEncounter().getLocation() == null) {
            return null;
        }
        return FacilityLocationScope.facilityUuid(order.getEncounter().getLocation());
    }

    private void publishSafely(String orderUuid, String scopeLocationUuid) {
        try {
            String payload = objectMapper.writeValueAsString(Collections.singletonMap("orderUuid", orderUuid));
            notificationService.publish(NotificationRequest.forPrivilegeAtLocation(
                    TOPIC, EVENT_TYPE, payload, REQUIRED_PRIVILEGE, scopeLocationUuid));
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("Unable to publish the laboratory result-ready notification", exception);
        }
    }

    public void setNotificationService(NotificationService notificationService) {
        if (notificationService == null) {
            throw new IllegalArgumentException("Notification service is required");
        }
        this.notificationService = notificationService;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Object mapper is required");
        }
        this.objectMapper = objectMapper;
    }
}
