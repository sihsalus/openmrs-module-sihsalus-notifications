package org.openmrs.module.sihsalusnotifications.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;
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

            new XmlBeanDefinitionReader(context).loadBeanDefinitions("classpath:moduleApplicationContext.xml");
            context.refresh();

            Map<String, NotificationService> services = context.getBeansOfType(NotificationService.class);
            assertEquals(1, services.size());
            assertTrue(services.get("sihsalusNotificationService") instanceof InMemoryNotificationService);
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
