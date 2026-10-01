package org.openmrs.module.sihsalusnotifications.web;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.*;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.sihsalusnotifications.api.DoctorResultInbox;

public class DoctorResultInboxFilterTest {
 private DoctorResultInbox inbox; private DoctorResultInboxFilter filter;
 private HttpServletRequest req; private HttpServletResponse res; private User user;
 @Before public void setup() throws Exception {
  inbox=mock(DoctorResultInbox.class);filter=new DoctorResultInboxFilter(inbox);
  req=mock(HttpServletRequest.class);res=mock(HttpServletResponse.class);
  when(req.getContextPath()).thenReturn("/openmrs");when(req.getRequestURI()).thenReturn("/openmrs/ws/sihsalus/notifications/results");when(req.getMethod()).thenReturn("GET");
  when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
  user=mock(User.class);when(user.getUuid()).thenReturn("doctor");Set<Privilege> privileges=new HashSet<Privilege>();for(String name:DoctorResultInbox.READ_PRIVILEGES)privileges.add(new Privilege(name));when(user.getPrivileges()).thenReturn(privileges);
  Location facility=new Location();facility.setUuid("facility");LocationTag tag=new LocationTag();tag.setName("Facility Location");facility.addTag(tag);
  UserContext uc=mock(UserContext.class);when(uc.getAuthenticatedUser()).thenReturn(user);when(uc.getLocation()).thenReturn(facility);
  HttpSession session=mock(HttpSession.class);when(session.getAttribute(AuthenticatedSessionResolver.OPENMRS_USER_CONTEXT_ATTRIBUTE)).thenReturn(uc);when(req.getSession(false)).thenReturn(session);
  when(inbox.list(user,"facility",0)).thenReturn(Collections.singletonMap("total",0));
 }
 @Test public void anonymousAndRetiredSessionsAreRejected() throws Exception {
  when(user.getRetired()).thenReturn(true);filter.doFilter(req,res,mock(FilterChain.class));verify(res).sendError(401);verifyNoInteractions(inbox);
 }
 @Test public void permissionDeniedCannotListEvenWithSameFacility() throws Exception {
  when(user.getPrivileges()).thenReturn(Collections.emptySet());filter.doFilter(req,res,mock(FilterChain.class));verify(res).sendError(403);verifyNoInteractions(inbox);
 }
 @Test public void listUsesCurrentUserAndNeverCachesClinicalDetails() throws Exception {
  filter.doFilter(req,res,mock(FilterChain.class));verify(inbox).list(user,"facility",0);verify(res).setHeader("Cache-Control","no-store, no-cache, must-revalidate");
 }
 private void post(String origin) {
  when(req.getMethod()).thenReturn("POST");when(req.getRequestURI()).thenReturn("/openmrs/ws/sihsalus/notifications/results/9/review");when(req.getContentType()).thenReturn("application/json");
  when(req.getHeader("Origin")).thenReturn(origin);when(req.getHeader("Host")).thenReturn("qlty.example");when(req.getHeader("X-Forwarded-Proto")).thenReturn("https");when(req.getRequestURL()).thenReturn(new StringBuffer("https://qlty.example/openmrs/ws/sihsalus/notifications/results/9/review"));
 }
 @Test public void crossSiteOrMissingOriginCannotMarkReviewed() throws Exception {
  post("https://other.example");filter.doFilter(req,res,mock(FilterChain.class));verify(res).sendError(403);verifyNoInteractions(inbox);
 }
 @Test public void reviewUsesOwnIdentityAndUnknownOrForeignIdReturns404() throws Exception {
  post("https://qlty.example");filter.doFilter(req,res,mock(FilterChain.class));verify(inbox).review(user,"facility",9);verify(res).sendError(404);
 }
 @Test public void reviewSuccessAndInvalidOffset() throws Exception {
  post("https://qlty.example");when(inbox.review(user,"facility",9)).thenReturn(true);filter.doFilter(req,res,mock(FilterChain.class));verify(res).setStatus(204);
  when(req.getMethod()).thenReturn("GET");when(req.getRequestURI()).thenReturn("/openmrs/ws/sihsalus/notifications/results");when(req.getParameter("offset")).thenReturn("-1");filter.doFilter(req,res,mock(FilterChain.class));verify(res).sendError(400);
 }
}
