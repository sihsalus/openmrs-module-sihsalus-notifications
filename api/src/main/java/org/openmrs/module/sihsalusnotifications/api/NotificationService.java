package org.openmrs.module.sihsalusnotifications.api;

import java.util.Set;

import org.openmrs.api.OpenmrsService;

public interface NotificationService extends OpenmrsService {

    NotificationEvent publish(NotificationRequest request);

    NotificationSubscription subscribe(SubscriberIdentity identity, Set<String> topics,
            NotificationListener listener);

    default NotificationSubscription subscribe(SubscriberIdentity identity, Set<String> topics,
            NotificationListener listener, String lastEventId) {
        return subscribe(identity, topics, listener);
    }

    int getSubscriberCount();

    default NotificationMetrics getMetrics() {
        return new NotificationMetrics(getSubscriberCount(), 0, 0L, 0L, 0L, 0L, 0L);
    }
}
