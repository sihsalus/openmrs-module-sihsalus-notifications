package org.openmrs.module.sihsalusnotifications.api.impl;

import org.openmrs.module.sihsalusnotifications.api.NotificationClock;

public class SystemNotificationClock implements NotificationClock {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
