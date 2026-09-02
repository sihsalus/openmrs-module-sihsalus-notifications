package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.servlet.http.HttpSession;

import org.junit.Test;
import org.openmrs.Privilege;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

public class AuthenticatedSessionResolverTest {

    private final AuthenticatedSessionResolver resolver = new AuthenticatedSessionResolver();

    @Test
    public void derivesUuidPrivilegesAndSuperuserStatusFromTheOpenmrsSession() {
        HttpSession session = mock(HttpSession.class);
        UserContext userContext = mock(UserContext.class);
        User user = mock(User.class);
        Privilege privilege = mock(Privilege.class);
        when(session.getAttribute(AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE))
                .thenReturn(userContext);
        when(userContext.getAuthenticatedUser()).thenReturn(user);
        when(user.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(user.getRetired()).thenReturn(false);
        when(user.isSuperUser()).thenReturn(true);
        when(privilege.getPrivilege()).thenReturn("View Queue");
        when(user.getPrivileges()).thenReturn(Collections.singleton(privilege));

        SubscriberIdentity identity = resolver.resolve(session);

        assertEquals(user.getUuid(), identity.getUserUuid());
        assertTrue(identity.hasPrivilege("View Queue"));
        assertTrue(identity.hasPrivilege("Any superuser privilege"));
    }

    @Test
    public void rejectsMissingAnonymousAndRetiredSessions() {
        assertNull(resolver.resolve(null));

        HttpSession session = mock(HttpSession.class);
        UserContext userContext = mock(UserContext.class);
        User user = mock(User.class);
        when(session.getAttribute(AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE))
                .thenReturn(userContext);
        when(userContext.getAuthenticatedUser()).thenReturn(user);
        when(user.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(user.getRetired()).thenReturn(true);
        assertNull(resolver.resolve(session));
    }
}
