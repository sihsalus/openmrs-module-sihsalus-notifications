package org.openmrs.module.sihsalusnotifications.api;

import java.util.*;
import java.util.function.Supplier;
import org.openmrs.*;
import org.openmrs.api.OrderService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.notification.Alert;
import org.openmrs.notification.AlertService;
import org.openmrs.notification.AlertRecipient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the core durable Alert/AlertRecipient model; SSE is only a refresh hint. */
public class DoctorResultInbox {
    public static final String PRIVILEGE = "app:hoja.clinica.ordenes";
    public static final List<String> READ_PRIVILEGES = Collections.unmodifiableList(Arrays.asList(
            PRIVILEGE, "Get Orders", "Get Patients", "Get Observations"));
    public static final String TOPIC = "clinical-results";
    private static final String PREFIX = "SIHSALUS:LAB_RESULT_READY:v1:";
    private static final int PAGE_SIZE = 20;
    private org.hibernate.SessionFactory sessionFactory;
    public void setSessionFactory(org.hibernate.SessionFactory value) { sessionFactory = value; }
    private AlertService alerts;
    private OrderService orders;
    private UserService users;
    private PlatformTransactionManager transactionManager;

    public void setAlerts(AlertService value) { alerts = value; }
    public void setOrders(OrderService value) { orders = value; }
    public void setUsers(UserService value) { users = value; }
    public void setTransactionManager(PlatformTransactionManager value) { transactionManager = value; }

    /** A new transaction is necessary when called by an afterCommit callback. */
    public User recordCompleted(String orderUuid) {
        return writeTransaction(() -> {
            Order order = orders.getOrderByUuid(orderUuid);
            if (!eligible(order)) return null;
            // Serialize completion retries on the existing order row, also across nodes.
            sessionFactory.getCurrentSession().buildLockRequest(new org.hibernate.LockOptions(
                    org.hibernate.LockMode.PESSIMISTIC_WRITE)).lock(order);
            Person person = order.getOrderer().getPerson();
            List<User> candidates = withPrivilege("Get Users", () -> users.getUsersByPerson(person, false));
            // Never guess between accounts linked to the same clinical provider.
            if (candidates.size() != 1) return null;
            User recipient = candidates.get(0);
            if (Boolean.TRUE.equals(recipient.getRetired())) return null;
            for (String privilege : READ_PRIVILEGES) if (!recipient.hasPrivilege(privilege)) return null;
            String text = PREFIX + order.getUuid();
            for (Alert existing : alerts.getAlerts(recipient, true, true)) {
                if (text.equals(existing.getText())) return null; // completed retry is idempotent
            }
            Alert alert = new Alert(text, recipient);
            alert.setSatisfiedByAny(false);
            save(alert);
            return recipient;
        });
    }

    public Map<String, Object> list(User user, String facilityUuid, int offset) {
        return writeTransaction(() -> listCurrent(user, facilityUuid, offset));
    }

    private Map<String, Object> listCurrent(User user, String facilityUuid, int offset) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Alert alert : alerts.getAlerts(user, false, false)) {
            Order order = ownedOrder(alert, user, facilityUuid);
            AlertRecipient recipient = alert.getRecipient(user);
            if (order == null || recipient == null || Boolean.TRUE.equals(recipient.getAlertRead())) continue;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", alert.getAlertId());
            row.put("orderUuid", order.getUuid());
            row.put("patientUuid", order.getPatient().getUuid());
            row.put("patientName", order.getPatient().getPersonName().getFullName());
            row.put("testName", order.getConcept().getName().getName());
            row.put("createdAt", alert.getDateCreated().toInstant().toString());
            rows.add(row);
        }
        Collections.sort(rows, (a,b) -> {
            int dateOrder = ((String)b.get("createdAt")).compareTo((String)a.get("createdAt"));
            return dateOrder != 0 ? dateOrder : Integer.compare((Integer)b.get("id"), (Integer)a.get("id"));
        });
        int end = Math.min(rows.size(), offset + PAGE_SIZE);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("results", offset < rows.size() ? rows.subList(offset, end) : Collections.emptyList());
        response.put("total", rows.size());
        response.put("hasMore", end < rows.size());
        return response;
    }

    /** Review is explicit and belongs to this recipient; opening the panel never writes. */
    public boolean review(User user, String facilityUuid, int id) {
        return writeTransaction(() -> {
            Alert alert = alerts.getAlert(id);
            if (ownedOrder(alert, user, facilityUuid) == null) return false;
            AlertRecipient recipient = alert.getRecipient(user);
            if (recipient == null) return false;
            if (!Boolean.TRUE.equals(recipient.getAlertRead())) {
                recipient.setAlertRead(true);
                recipient.setDateChanged(new Date());
                save(alert);
            }
            return true;
        });
    }

    private Order ownedOrder(Alert alert, User user, String facilityUuid) {
        if (alert == null || user == null || facilityUuid == null || alert.getRecipient(user) == null
                || alert.getText() == null || !alert.getText().startsWith(PREFIX)) return null;
        String uuid = alert.getText().substring(PREFIX.length());
        if (!uuid.matches("[0-9a-fA-F-]{36}")) return null;
        Order order = orders.getOrderByUuid(uuid);
        if (!eligible(order) || !user.getPerson().equals(order.getOrderer().getPerson())
                || !facilityUuid.equals(FacilityLocationScope.facilityUuid(order.getEncounter().getLocation()))) return null;
        return order;
    }

    private boolean eligible(Order order) {
        return order instanceof TestOrder && !Boolean.TRUE.equals(order.getVoided())
                && order.getFulfillerStatus() == Order.FulfillerStatus.COMPLETED
                && order.getPatient() != null && !Boolean.TRUE.equals(order.getPatient().getVoided())
                && order.getEncounter() != null && !Boolean.TRUE.equals(order.getEncounter().getVoided())
                && hasResult(order)
                && order.getOrderer() != null && !Boolean.TRUE.equals(order.getOrderer().getRetired())
                && order.getOrderer().getPerson() != null
                && FacilityLocationScope.facilityUuid(order.getEncounter().getLocation()) != null;
    }

    private boolean hasResult(Order order) {
        for (Obs obs : order.getEncounter().getObsAtTopLevel(false)) {
            if (!Boolean.TRUE.equals(obs.getVoided()) && obs.getOrder() != null
                    && order.getUuid().equals(obs.getOrder().getUuid())
                    && order.getPatient().equals(obs.getPerson())
                    && order.getConcept().equals(obs.getConcept())) return true;
        }
        return false;
    }

    protected void save(Alert alert) {
        withPrivilege("Manage Alerts", () -> alerts.saveAlert(alert));
    }

    protected <T> T withPrivilege(String privilege, Supplier<T> operation) {
        boolean granted = !Context.hasPrivilege(privilege);
        if (granted) Context.addProxyPrivilege(privilege);
        try { return operation.get(); }
        finally { if (granted) Context.removeProxyPrivilege(privilege); }
    }

    private <T> T writeTransaction(Supplier<T> operation) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> operation.get());
    }
}
