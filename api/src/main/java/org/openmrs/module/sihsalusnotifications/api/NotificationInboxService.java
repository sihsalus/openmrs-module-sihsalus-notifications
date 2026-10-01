package org.openmrs.module.sihsalusnotifications.api;

import java.util.Map;
import org.openmrs.User;
import org.openmrs.api.OpenmrsService;

public interface NotificationInboxService extends OpenmrsService {
    /** Trusted server-side creation; the registered type derives recipient and scope. */
    void publishAfterCommit(String type, String subjectUuid);
    Map<String, Object> list(User user, String facilityUuid, int offset);
    /** Notification acknowledgement only, never clinical approval/signature. */
    boolean markRead(User user, String facilityUuid, int id);
}
