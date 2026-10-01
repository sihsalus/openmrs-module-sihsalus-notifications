package org.openmrs.module.sihsalusnotifications.api;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openmrs.*;
import org.openmrs.api.OrderService;
import org.openmrs.api.UserService;
import org.openmrs.notification.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class NotificationInboxTest {
    private NotificationInbox inbox;
    private org.openmrs.module.sihsalusnotifications.adapters.laboratory.LaboratoryResultNotificationType adapter;
    private NotificationService signals;
    private List<NotificationInboxType> types;
    private AlertService alerts;
    private UserService users;
    private OrderService orders;
    private PlatformTransactionManager tx;
    private TestOrder order;
    private User doctor;
    private Location facility;

    @Before public void setup() {
        alerts=mock(AlertService.class); users=mock(UserService.class); orders=mock(OrderService.class);
        tx=mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        adapter = new org.openmrs.module.sihsalusnotifications.adapters.laboratory.LaboratoryResultNotificationType() {
            @Override protected List<User> requesterAccounts(Person person) { return users.getUsersByPerson(person, false); }
        };
        types = new ArrayList<NotificationInboxType>(); types.add(adapter);
        signals = mock(NotificationService.class);
        inbox=new NotificationInbox() { @Override protected <T> T withPrivilege(String p, Supplier<T> action) { return action.get(); }
            @Override protected List<NotificationInboxType> types() { return types; } };
        org.hibernate.SessionFactory sf=mock(org.hibernate.SessionFactory.class);
        org.hibernate.Session session=mock(org.hibernate.Session.class);
        when(sf.getCurrentSession()).thenReturn(session);
        when(session.buildLockRequest(any(org.hibernate.LockOptions.class))).thenReturn(mock(org.hibernate.Session.LockRequest.class));
        inbox.setSessionFactory(sf);
        inbox.setAlerts(alerts); adapter.setOrders(orders); adapter.setUsers(users); inbox.setNotifications(signals); inbox.setTransactionManager(tx);
        Person person=new Person(10);
        doctor=mock(User.class); when(doctor.getPerson()).thenReturn(person); when(doctor.hasPrivilege(anyString())).thenReturn(true);
        when(doctor.getUuid()).thenReturn("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        facility=new Location(); facility.setUuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        LocationTag tag=new LocationTag();tag.setName("Facility Location");facility.addTag(tag);
        Provider provider=new Provider();provider.setPerson(person);
        Patient patient=new Patient(11);PersonName name=new PersonName();name.setGivenName("SYNTHETIC");name.setFamilyName("Inbox");patient.addName(name);patient.setUuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        Concept concept=mock(Concept.class); when(concept.getName()).thenReturn(new ConceptName("Test",Locale.ENGLISH));
        Encounter encounter=new Encounter();encounter.setLocation(facility);encounter.setPatient(patient);
        order=new TestOrder();order.setUuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd");order.setOrderer(provider);order.setPatient(patient);order.setConcept(concept);order.setEncounter(encounter);order.setFulfillerStatus(Order.FulfillerStatus.COMPLETED);
        Obs obs=new Obs();obs.setPerson(patient);obs.setConcept(concept);obs.setOrder(order);obs.setValueNumeric(0.0);encounter.addObs(obs);
        when(orders.getOrderByUuid(order.getUuid())).thenReturn(order);
        when(users.getUsersByPerson(person,false)).thenReturn(Collections.singletonList(doctor));
        when(alerts.getAlerts(doctor,true,true)).thenReturn(Collections.emptyList());
    }
    private Alert savedAlert() {
        assertTrue(inbox.create(adapter.getName(), order.getUuid()));
        ArgumentCaptor<Alert> c=ArgumentCaptor.forClass(Alert.class);verify(alerts).saveAlert(c.capture());
        Alert a=c.getValue();a.setAlertId(7);a.setDateCreated(new Date());return a;
    }
    @Test public void storesMinimalDurableAlertInSeparateTransactionWithZeroResult() {
        Alert a=savedAlert();assertTrue(a.getText().endsWith(order.getUuid()));assertFalse(a.getText().contains("SYNTHETIC"));
        assertNotNull(a.getRecipient(doctor));
        ArgumentCaptor<TransactionDefinition> c=ArgumentCaptor.forClass(TransactionDefinition.class);verify(tx).getTransaction(c.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,c.getValue().getPropagationBehavior());
        verify(tx).commit(any());
    }
    @Test public void doesNotDuplicateReviewedOrPendingAlert() {
        Alert a=savedAlert();a.getRecipient(doctor).setAlertRead(true);when(alerts.getAlerts(doctor,true,true)).thenReturn(Collections.singletonList(a));
        assertFalse(inbox.create(adapter.getName(), order.getUuid()));verify(alerts,times(1)).saveAlert(any());
    }
    @Test public void rejectsAmbiguousRetiredAndUnauthorizedAccounts() {
        when(users.getUsersByPerson(doctor.getPerson(),false)).thenReturn(Arrays.asList(doctor,doctor));assertFalse(inbox.create(adapter.getName(), order.getUuid()));
        when(users.getUsersByPerson(doctor.getPerson(),false)).thenReturn(Collections.singletonList(doctor));when(doctor.getRetired()).thenReturn(true);assertFalse(inbox.create(adapter.getName(), order.getUuid()));
        when(doctor.getRetired()).thenReturn(false);when(doctor.hasPrivilege("app:hoja.clinica.ordenes")).thenReturn(false);assertFalse(inbox.create(adapter.getName(), order.getUuid()));verify(alerts,never()).saveAlert(any());
    }
    @Test public void missingVoidedOrIncompleteResultsDoNotNotify() {
        order.setFulfillerStatus(Order.FulfillerStatus.IN_PROGRESS);assertFalse(inbox.create(adapter.getName(), order.getUuid()));
        order.setFulfillerStatus(Order.FulfillerStatus.COMPLETED);for (Obs obs : order.getEncounter().getObs()) obs.setVoided(true);assertFalse(inbox.create(adapter.getName(), order.getUuid()));
        order.setVoided(true);assertFalse(inbox.create(adapter.getName(), order.getUuid()));verify(alerts,never()).saveAlert(any());
    }
    @Test public void listAndReviewAreScopedToRecipientAndFacility() {
        Alert a=savedAlert();when(alerts.getAlerts(doctor,false,false)).thenReturn(Collections.singletonList(a));when(alerts.getAlert(7)).thenReturn(a);
        assertEquals(1,inbox.list(doctor,facility.getUuid(),0).get("total"));assertEquals(0,inbox.list(doctor,"other-facility",0).get("total"));
        assertFalse(inbox.markRead(doctor,"other-facility",7));assertFalse(inbox.markRead(mock(User.class),facility.getUuid(),7));
        assertTrue(inbox.markRead(doctor,facility.getUuid(),7));assertTrue(a.getRecipient(doctor).getAlertRead());
        assertTrue(inbox.markRead(doctor,facility.getUuid(),7));verify(alerts,times(2)).saveAlert(any());
        assertEquals(0,inbox.list(doctor,facility.getUuid(),0).get("total"));
    }
    @Test public void openingOrPagingDoesNotMarkReadAndIgnoresUnrelatedAlerts() {
        Alert a=savedAlert();Alert other=new Alert("Other system alert",doctor);
        when(alerts.getAlerts(doctor,false,false)).thenReturn(Arrays.asList(other,a));
        assertEquals(1,inbox.list(doctor,facility.getUuid(),0).get("total"));assertEquals(Collections.emptyList(),inbox.list(doctor,facility.getUuid(),20).get("items"));
        assertFalse(Boolean.TRUE.equals(a.getRecipient(doctor).getAlertRead()));verify(alerts,times(1)).saveAlert(any());
    }
    @Test public void supportsAnUnrelatedRegisteredModuleWithoutKernelChanges() {
        NotificationInboxType other = mock(NotificationInboxType.class);
        when(other.getName()).thenReturn("interconsultation-ready");
        when(other.getReadPrivileges()).thenReturn(Collections.singleton("View Referrals"));
        when(other.recipient(order.getUuid())).thenReturn(new NotificationInboxType.Delivery(doctor, facility.getUuid()));
        Map<String,Object> content = new HashMap<String,Object>();
        content.put("title", "Synthetic referral"); content.put("subtitle", "Answered");
        when(other.resolve(order.getUuid(), doctor, facility.getUuid())).thenReturn(content);
        types.add(other);
        assertTrue(inbox.create(other.getName(), order.getUuid()));
        ArgumentCaptor<Alert> capture = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).saveAlert(capture.capture());
        Alert alert = capture.getValue(); alert.setAlertId(9); alert.setDateCreated(new Date());
        when(alerts.getAlerts(doctor, false, false)).thenReturn(Collections.singletonList(alert));
        when(alerts.getAlert(9)).thenReturn(alert);
        assertEquals(1, inbox.list(doctor, facility.getUuid(), 0).get("total"));
        when(doctor.hasPrivilege("View Referrals")).thenReturn(false);
        assertEquals(0, inbox.list(doctor, facility.getUuid(), 0).get("total"));
        assertFalse(inbox.markRead(doctor, facility.getUuid(), 9));
        verify(signals).publish(argThat(event -> event.getTopic().equals("notifications")
                && event.getRecipientUserUuid().equals(doctor.getUuid())
                && event.getScopeLocationUuid().equals(facility.getUuid())
                && event.getPayloadJson().equals("{}")));
    }
    @Test public void rejectsUnknownDuplicateAndRemovedTypesWithoutReadingForeignData() {
        assertFalse(inbox.create("unknown", order.getUuid()));
        assertFalse(inbox.create(adapter.getName(), "------------------------------------"));
        Alert a = savedAlert();
        when(alerts.getAlerts(doctor, false, false)).thenReturn(Collections.singletonList(a));
        when(alerts.getAlert(7)).thenReturn(a);
        types.clear();
        assertEquals(0, inbox.list(doctor, facility.getUuid(), 0).get("total"));
        assertFalse(inbox.markRead(doctor, facility.getUuid(), 7));
        types.add(adapter); types.add(adapter);
        try { inbox.list(doctor, facility.getUuid(), 0); fail("Duplicate type must fail closed"); }
        catch (IllegalStateException expected) { }
    }
    @Test public void durablePersistenceRemainsSuccessfulIfRealtimeSignalFails() {
        when(signals.publish(any())).thenThrow(new IllegalStateException("synthetic transport failure"));
        assertTrue(inbox.create(adapter.getName(), order.getUuid()));
        verify(alerts).saveAlert(any()); verify(tx).commit(any());
    }
    @Test public void genericPublicationWaitsForClinicalCommitAndDoesNotRunOnRollback() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            inbox.publishAfterCommit(adapter.getName(), order.getUuid());
            verify(alerts, never()).saveAlert(any());
            org.springframework.transaction.support.TransactionSynchronization callback =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().get(0);
            callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(alerts, never()).saveAlert(any());
            callback.afterCommit();
            verify(alerts).saveAlert(any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
