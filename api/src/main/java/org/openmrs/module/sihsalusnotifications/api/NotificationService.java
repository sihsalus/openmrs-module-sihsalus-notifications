package org.openmrs.module.sihsalusnotifications.api;

import java.util.Set;

import org.openmrs.api.OpenmrsService;

public interface NotificationService extends OpenmrsService {

    NotificationEvent publish(NotificationRequest request);

    NotificationSubscription subscribe(SubscriberIdentity identity, Set<String> topics,
            NotificationListener listener);

    int getSubscriberCount();
}
