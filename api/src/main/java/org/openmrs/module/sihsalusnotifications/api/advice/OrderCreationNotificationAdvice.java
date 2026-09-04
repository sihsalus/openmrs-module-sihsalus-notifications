package org.openmrs.module.sihsalusnotifications.api.advice;

import java.util.Collections;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.DrugOrder;
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
 * Publishes privacy-minimized events after new medication or laboratory orders commit.
 *
 * <p>Only genuinely new {@link Order.Action#NEW} orders are accepted. Revisions, renewals,
 * discontinuations, and repeated saves of an already persisted order do not emit events.</p>
 */
public final class OrderCreationNotificationAdvice implements MethodInterceptor {

    public static final String LABORATORY_TOPIC = "laboratory";

    public static final String LABORATORY_EVENT_TYPE = "LAB_ORDER_CREATED";

    public static final String LABORATORY_PRIVILEGE = "app:home.laboratorio";

    public static final String PHARMACY_TOPIC = "pharmacy";

    public static final String PHARMACY_EVENT_TYPE = "MEDICATION_ORDER_CREATED";

    public static final String PHARMACY_PRIVILEGE = "app:home.farmacia";

    private static final String SAVE_ORDER_METHOD = "saveOrder";

    private static final String SAVE_RETROSPECTIVE_ORDER_METHOD = "saveRetrospectiveOrder";

    private static final Logger log = LoggerFactory.getLogger(OrderCreationNotificationAdvice.class);

    private NotificationService notificationService;

    private ObjectMapper objectMapper;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        boolean newOrder = isNewOrderInvocation(invocation);
        Object result = invocation.proceed();
        NotificationTarget target = newOrder ? targetFor(result) : null;
        if (target == null) {
            return result;
        }

        Order order = (Order) result;
        final String orderUuid = order.getUuid();
        final String scopeLocationUuid = scopeLocationUuid(order);
        if (scopeLocationUuid == null) {
            log.warn("Skipping an order-created notification without a tagged facility scope");
            return result;
        }
        Runnable publish = new Runnable() {

            @Override
            public void run() {
                publishSafely(target, orderUuid, scopeLocationUuid);
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

    private boolean isNewOrderInvocation(MethodInvocation invocation) {
        String methodName = invocation.getMethod().getName();
        if (!SAVE_ORDER_METHOD.equals(methodName) && !SAVE_RETROSPECTIVE_ORDER_METHOD.equals(methodName)) {
            return false;
        }

        Object[] arguments = invocation.getArguments();
        if (arguments.length == 0 || !(arguments[0] instanceof Order)) {
            return false;
        }

        Order order = (Order) arguments[0];
        return order.getOrderId() == null && order.getAction() == Order.Action.NEW;
    }

    private NotificationTarget targetFor(Object result) {
        if (!(result instanceof Order)) {
            return null;
        }

        Order order = (Order) result;
        if (order.getUuid() == null || order.getUuid().trim().isEmpty()) {
            return null;
        }
        if (order instanceof DrugOrder) {
            return new NotificationTarget(PHARMACY_TOPIC, PHARMACY_EVENT_TYPE, PHARMACY_PRIVILEGE);
        }
        if (order instanceof TestOrder) {
            return new NotificationTarget(LABORATORY_TOPIC, LABORATORY_EVENT_TYPE, LABORATORY_PRIVILEGE);
        }
        return null;
    }

    private String scopeLocationUuid(Order order) {
        if (order.getEncounter() == null || order.getEncounter().getLocation() == null) {
            return null;
        }
        return FacilityLocationScope.facilityUuid(order.getEncounter().getLocation());
    }

    private void publishSafely(NotificationTarget target, String orderUuid, String scopeLocationUuid) {
        try {
            String payload = objectMapper.writeValueAsString(Collections.singletonMap("orderUuid", orderUuid));
            notificationService.publish(NotificationRequest.forPrivilegeAtLocation(
                    target.topic, target.eventType, payload, target.requiredPrivilege,
                    scopeLocationUuid));
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("Unable to publish the order-created notification", exception);
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

    private static final class NotificationTarget {

        private final String topic;

        private final String eventType;

        private final String requiredPrivilege;

        private NotificationTarget(String topic, String eventType, String requiredPrivilege) {
            this.topic = topic;
            this.eventType = eventType;
            this.requiredPrivilege = requiredPrivilege;
        }
    }
}
