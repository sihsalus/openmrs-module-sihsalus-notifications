package org.openmrs.module.sihsalusnotifications.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SubscriberIdentity {

    private final String userUuid;

    private final Set<String> privileges;

    private final boolean superUser;

    private final String locationUuid;

    public SubscriberIdentity(String userUuid, Set<String> privileges) {
        this(userUuid, privileges, false, null);
    }

    public SubscriberIdentity(String userUuid, Set<String> privileges, boolean superUser) {
        this(userUuid, privileges, superUser, null);
    }

    public SubscriberIdentity(String userUuid, Set<String> privileges, boolean superUser,
            String locationUuid) {
        if (userUuid == null || userUuid.trim().isEmpty()) {
            throw new IllegalArgumentException("Subscriber user UUID is required");
        }
        this.userUuid = userUuid;
        this.privileges = privileges == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(privileges));
        this.superUser = superUser;
        this.locationUuid = locationUuid == null || locationUuid.trim().isEmpty()
                ? null : locationUuid;
    }

    public String getUserUuid() {
        return userUuid;
    }

    public boolean hasPrivilege(String privilege) {
        return superUser || privileges.contains(privilege);
    }

    public Set<String> getPrivileges() {
        return privileges;
    }

    public boolean isSuperUser() {
        return superUser;
    }

    public String getLocationUuid() {
        return locationUuid;
    }
}
