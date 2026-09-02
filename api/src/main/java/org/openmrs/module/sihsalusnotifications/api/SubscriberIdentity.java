package org.openmrs.module.sihsalusnotifications.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SubscriberIdentity {

    private final String userUuid;

    private final Set<String> privileges;

    private final boolean superUser;

    public SubscriberIdentity(String userUuid, Set<String> privileges) {
        this(userUuid, privileges, false);
    }

    public SubscriberIdentity(String userUuid, Set<String> privileges, boolean superUser) {
        if (userUuid == null || userUuid.trim().isEmpty()) {
            throw new IllegalArgumentException("Subscriber user UUID is required");
        }
        this.userUuid = userUuid;
        this.privileges = privileges == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(privileges));
        this.superUser = superUser;
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
}
