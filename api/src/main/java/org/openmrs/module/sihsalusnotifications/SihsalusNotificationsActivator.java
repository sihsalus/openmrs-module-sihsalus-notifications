package org.openmrs.module.sihsalusnotifications;

import org.openmrs.api.OrderService;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.sihsalusnotifications.adapters.laboratory.LaboratoryResultNotificationAdvice;
import org.openmrs.module.sihsalusnotifications.adapters.orders.OrderCreationNotificationAdvice;

/**
 * OpenMRS lifecycle entry point for the realtime notifications module.
 *
 * <p>The transports are registered through the module web descriptor. A concrete activator is
 * still required because OpenMRS resolves the configured lifecycle class before it installs the
 * module's servlets and filters.</p>
 */
public final class SihsalusNotificationsActivator extends BaseModuleActivator {

    private LaboratoryResultNotificationAdvice laboratoryResultNotificationAdvice;

    private OrderCreationNotificationAdvice orderCreationNotificationAdvice;

    @Override
    public void started() {
        laboratoryResultNotificationAdvice = Context.getRegisteredComponent(
                "sihsalusLaboratoryResultNotificationAdvice", LaboratoryResultNotificationAdvice.class);
        orderCreationNotificationAdvice = Context.getRegisteredComponent(
                "sihsalusOrderCreationNotificationAdvice", OrderCreationNotificationAdvice.class);
        Context.addAdvice(OrderService.class, laboratoryResultNotificationAdvice);
        Context.addAdvice(OrderService.class, orderCreationNotificationAdvice);
    }

    @Override
    public void stopped() {
        if (laboratoryResultNotificationAdvice != null) {
            Context.removeAdvice(OrderService.class, laboratoryResultNotificationAdvice);
            laboratoryResultNotificationAdvice = null;
        }
        if (orderCreationNotificationAdvice != null) {
            Context.removeAdvice(OrderService.class, orderCreationNotificationAdvice);
            orderCreationNotificationAdvice = null;
        }
    }
}
