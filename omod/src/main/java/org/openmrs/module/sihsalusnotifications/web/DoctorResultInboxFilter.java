package org.openmrs.module.sihsalusnotifications.web;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.DoctorResultInbox;
import org.openmrs.module.sihsalusnotifications.api.SubscriberIdentity;

/** Only the authenticated user's own, facility-scoped completed orders are exposed. */
public class DoctorResultInboxFilter implements Filter {
    private static final String PATH = "/ws/sihsalus/notifications/results";
    private final AuthenticatedSessionResolver resolver = new AuthenticatedSessionResolver();
    private final ObjectMapper mapper = new ObjectMapper();
    private final DoctorResultInbox injectedInbox;
    public DoctorResultInboxFilter() { this(null); }
    DoctorResultInboxFilter(DoctorResultInbox inbox) { injectedInbox = inbox; }
    public void init(FilterConfig config) { }
    public void destroy() { }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest)request;
        HttpServletResponse res = (HttpServletResponse)response;
        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        res.setDateHeader("Expires", 0L);
        SubscriberIdentity identity = resolver.resolve(req.getSession(false));
        if (identity == null) { res.sendError(401); return; }
        if (!DoctorResultInbox.READ_PRIVILEGES.stream().allMatch(identity::hasPrivilege) || identity.getLocationUuid() == null) {
            res.sendError(403); return;
        }
        User user = ((UserContext)req.getSession(false).getAttribute(
                AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE)).getAuthenticatedUser();
        String path = req.getRequestURI().substring(req.getContextPath().length());
        DoctorResultInbox inbox = injectedInbox == null
                ? Context.getRegisteredComponent("sihsalusDoctorResultInbox", DoctorResultInbox.class) : injectedInbox;
        try {
            if ((PATH.equals(path) || (PATH + "/").equals(path)) && "GET".equals(req.getMethod())) {
                int offset = req.getParameter("offset") == null ? 0 : Integer.parseInt(req.getParameter("offset"));
                if (offset < 0 || offset > 1000000) { res.sendError(400); return; }
                res.setContentType("application/json");
                res.setCharacterEncoding("UTF-8");
                mapper.writeValue(res.getWriter(), inbox.list(user, identity.getLocationUuid(), offset));
            } else if (path.matches(PATH + "/[0-9]+/review") && "POST".equals(req.getMethod())) {
                // JSON + exact Origin prevent cookie-authenticated cross-site form mutations.
                if (req.getContentType() == null || !req.getContentType().startsWith("application/json")
                        || !sameOrigin(req)) { res.sendError(403); return; }
                int id = Integer.parseInt(path.substring((PATH + "/").length(), path.lastIndexOf('/')));
                if (!inbox.review(user, identity.getLocationUuid(), id)) { res.sendError(404); return; }
                res.setStatus(204);
            } else { res.sendError(405); }
        } catch (NumberFormatException exception) { res.sendError(400); }
    }

    private boolean sameOrigin(HttpServletRequest req) {
        Map<String,List<String>> headers = new HashMap<String,List<String>>();
        for (String name : Arrays.asList("Origin", "Host", "X-Forwarded-Proto")) {
            headers.put(name, Collections.singletonList(req.getHeader(name)));
        }
        return new WebSocketOriginPolicy(Collections.<String>emptySet()).isAllowed(
                URI.create(req.getRequestURL().toString()), headers);
    }
}
