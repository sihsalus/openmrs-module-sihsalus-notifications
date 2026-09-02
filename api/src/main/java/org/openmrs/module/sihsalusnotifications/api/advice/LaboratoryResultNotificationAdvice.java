package org.openmrs.module.sihsalusnotifications.api.advice;

import java.util.Collections;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Order;
import org.openmrs.TestOrder;
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
        Object result = invocation.proceed();
        if (!isCompletedLaboratoryOrder(invocation, result)) {
            return result;
        }

        final String orderUuid = ((Order) result).getUuid();
        Runnable publish = new Runnable() {

            @Override
            public void run() {
                publishSafely(orderUuid);
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

    private boolean isCompletedLaboratoryOrder(MethodInvocation invocation, Object result) {
        if (!UPDATE_METHOD.equals(invocation.getMethod().getName()) || !(result instanceof TestOrder)) {
            return false;
        }

        Object[] arguments = invocation.getArguments();
        return arguments.length >= 2
                && arguments[1] == Order.FulfillerStatus.COMPLETED
                && ((Order) result).getFulfillerStatus() == Order.FulfillerStatus.COMPLETED
                && ((Order) result).getUuid() != null
                && !((Order) result).getUuid().trim().isEmpty();
    }

    private void publishSafely(String orderUuid) {
        try {
            String payload = objectMapper.writeValueAsString(Collections.singletonMap("orderUuid", orderUuid));
            notificationService.publish(NotificationRequest.forPrivilege(
                    TOPIC, EVENT_TYPE, payload, REQUIRED_PRIVILEGE));
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
