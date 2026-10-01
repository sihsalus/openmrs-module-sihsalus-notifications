package org.openmrs.module.sihsalusnotifications.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.openmrs.module.sihsalusnotifications.api.advice.LaboratoryResultNotificationAdvice;
import org.openmrs.module.sihsalusnotifications.api.advice.OrderCreationNotificationAdvice;
import org.openmrs.module.sihsalusnotifications.api.impl.InMemoryNotificationService;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;

public class NotificationServiceSpringContextTest {

    @Test
    public void moduleContextRegistersTheRealtimeService() {
        GenericApplicationContext context = new GenericApplicationContext();
        try {
            RootBeanDefinition serviceContext = new RootBeanDefinition(ModuleServiceRegistrar.class);
            serviceContext.setAbstract(true);
            serviceContext.getPropertyValues().add("moduleService", new ManagedList<Object>());
            context.registerBeanDefinition("serviceContext", serviceContext);

            context.getBeanFactory().registerSingleton("sessionFactory", org.mockito.Mockito.mock(org.hibernate.SessionFactory.class));
            context.getBeanFactory().registerSingleton("alertService", org.mockito.Mockito.mock(org.openmrs.notification.AlertService.class));
            context.getBeanFactory().registerSingleton("orderService", org.mockito.Mockito.mock(org.openmrs.api.OrderService.class));
            context.getBeanFactory().registerSingleton("userService", org.mockito.Mockito.mock(org.openmrs.api.UserService.class));
            context.getBeanFactory().registerSingleton("transactionManager", org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
            new XmlBeanDefinitionReader(context).loadBeanDefinitions("classpath:moduleApplicationContext.xml");
            context.refresh();

            Map<String, NotificationService> services = context.getBeansOfType(NotificationService.class);
            assertEquals(1, services.size());
            assertTrue(services.get("sihsalusNotificationService") instanceof InMemoryNotificationService);
            assertTrue(context.getBean("sihsalusLaboratoryResultNotificationAdvice")
                    instanceof LaboratoryResultNotificationAdvice);
            assertTrue(context.getBean("sihsalusOrderCreationNotificationAdvice")
                    instanceof OrderCreationNotificationAdvice);
        } finally {
            context.close();
        }
    }

    public static class ModuleServiceRegistrar {

        public void setModuleService(List<Object> moduleService) {
            // The production parent registers the interface/service pair with OpenMRS.
        }
    }
}
