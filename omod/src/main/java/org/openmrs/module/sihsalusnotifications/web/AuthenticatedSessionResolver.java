package org.openmrs.module.sihsalusnotifications.web;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpSession;

import org.openmrs.Privilege;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

final class AuthenticatedSessionResolver {

    static final String OPENMRS_USER_CONTEXT_ATTRIBUTE = "__openmrs_user_context";

    SubscriberIdentity resolve(HttpSession session) {
        if (session == null) {
            return null;
        }
        try {
            Object value = session.getAttribute(OPENMRS_USER_CONTEXT_ATTRIBUTE);
            User user = value instanceof UserContext
                    ? ((UserContext) value).getAuthenticatedUser() : null;
            if (user == null || Boolean.TRUE.equals(user.getRetired()) || user.getUuid() == null) {
                return null;
            }

            Set<String> privilegeNames = new HashSet<String>();
            for (Privilege privilege : user.getPrivileges()) {
                if (privilege != null && privilege.getPrivilege() != null) {
                    privilegeNames.add(privilege.getPrivilege());
                }
            }
            return new SubscriberIdentity(user.getUuid(), privilegeNames, user.isSuperUser());
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
