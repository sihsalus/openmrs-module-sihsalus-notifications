package org.openmrs.module.sihsalusnotifications.api;

import java.util.Map;
import java.util.Set;
import org.openmrs.User;

/** Trusted Spring extension contributed by an OMOD. No browser-defined policies. */
public interface NotificationInboxType {
    String getName();
    Set<String> getReadPrivileges();
    Delivery recipient(String subjectUuid);
    /** Return null unless current domain ownership, facility and resource state permit access. */
    Map<String, Object> resolve(String subjectUuid, User user, String facilityUuid);

    final class Delivery {
        private final User user;
        private final String facilityUuid;
        public Delivery(User user, String facilityUuid) {
            this.user = user;
            this.facilityUuid = facilityUuid;
        }
        public User getUser() { return user; }
        public String getFacilityUuid() { return facilityUuid; }
    }
}
