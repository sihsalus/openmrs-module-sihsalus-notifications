package org.openmrs.module.sihsalusnotifications.api;

public interface NotificationSubscription extends AutoCloseable {

    @Override
    void close();

    default boolean isReplayComplete() {
        return true;
    }
}
