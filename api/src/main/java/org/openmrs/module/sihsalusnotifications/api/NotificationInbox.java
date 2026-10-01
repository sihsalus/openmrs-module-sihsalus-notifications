package org.openmrs.module.sihsalusnotifications.api;

import java.util.*;
import java.util.function.Supplier;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.SessionFactory;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.notification.Alert;
import org.openmrs.notification.AlertRecipient;
import org.openmrs.notification.AlertService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, domain-neutral inbox using the core Alert/AlertRecipient model. */
public class NotificationInbox implements NotificationInboxService {
    public static final String TOPIC = "notifications";
    public static final String EVENT = "NOTIFICATION_CREATED";
    private static final String PREFIX = "SIHSALUS:notification:v1:";
    private static final int PAGE_SIZE = 20;
    private AlertService alerts;
    private SessionFactory sessionFactory;
    private PlatformTransactionManager transactionManager;
    private NotificationService notifications;
    public void setAlerts(AlertService value) { alerts = value; }
    public void setSessionFactory(SessionFactory value) { sessionFactory = value; }
    public void setTransactionManager(PlatformTransactionManager value) { transactionManager = value; }
    public void setNotifications(NotificationService value) { notifications = value; }

    protected List<NotificationInboxType> types() {
        return Context.getRegisteredComponents(NotificationInboxType.class);
    }

    private Map<String, NotificationInboxType> registry() {
        Map<String, NotificationInboxType> registry = new HashMap<String, NotificationInboxType>();
        for (NotificationInboxType type : types()) {
            String name = type.getName();
            if (name == null || !name.matches("[a-z][a-z0-9-]{0,63}")
                    || registry.put(name, type) != null || type.getReadPrivileges() == null
                    || type.getReadPrivileges().isEmpty()
                    || type.getReadPrivileges().stream().anyMatch(p -> p == null || p.trim().isEmpty())) {
                throw new IllegalStateException("Invalid or duplicate inbox type registration");
            }
        }
        return registry;
    }

    private boolean authorized(User user, NotificationInboxType type) {
        return user != null && !Boolean.TRUE.equals(user.getRetired()) && type != null
                && type.getReadPrivileges().stream().allMatch(user::hasPrivilege);
    }

    @Override public void publishAfterCommit(String name, String subjectUuid) {
        Runnable persist = () -> {
            try { create(name, subjectUuid); }
            catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(NotificationInbox.class).warn("Unable to persist an inbox notification");
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Notification requires transaction synchronization");
            }
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { persist.run(); }
                    });
        } else {
            persist.run();
        }
    }

    protected boolean create(String name, String subjectUuid) {
        if (subjectUuid == null || !subjectUuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return false;
        NotificationInboxType type = registry().get(name);
        if (type == null) return false;
        NotificationInboxType.Delivery delivery = transaction(() -> {
            NotificationInboxType.Delivery target = type.recipient(subjectUuid);
            if (target == null || target.getFacilityUuid() == null || target.getFacilityUuid().trim().isEmpty()
                    || !authorized(target.getUser(), type)) return null;
            // Serialize per-recipient creation across nodes without knowing a clinical resource.
            sessionFactory.getCurrentSession().buildLockRequest(new LockOptions(LockMode.PESSIMISTIC_WRITE))
                    .lock(target.getUser());
            if (!authorized(target.getUser(), type)
                    || type.resolve(subjectUuid, target.getUser(), target.getFacilityUuid()) == null) return null;
            String text = PREFIX + name + ":" + subjectUuid;
            for (Alert existing : alerts.getAlerts(target.getUser(), true, true)) {
                if (text.equals(existing.getText())) return null;
            }
            Alert alert = new Alert(text, target.getUser());
            alert.setSatisfiedByAny(false);
            save(alert);
            return target;
        });
        if (delivery == null) return false;
        // The durable transaction has committed. An ephemeral signal failure cannot undo it.
        try {
            notifications.publish(NotificationRequest.forUserAtLocation(delivery.getUser().getUuid(), TOPIC,
                    EVENT, "{}", null, delivery.getFacilityUuid()));
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(NotificationInbox.class).warn("Unable to signal an inbox update");
        }
        return true;
    }

    @Override public Map<String, Object> list(User user, String facilityUuid, int offset) {
        if (offset < 0 || offset > 1000000) throw new IllegalArgumentException("Invalid inbox offset");
        return transaction(() -> {
            Map<String, NotificationInboxType> types = registry();
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            if (user != null && facilityUuid != null && !Boolean.TRUE.equals(user.getRetired())) {
                for (Alert alert : alerts.getAlerts(user, false, false)) {
                    Map<String, Object> row = resolve(alert, user, facilityUuid, types);
                    if (row != null && !Boolean.TRUE.equals(alert.getRecipient(user).getAlertRead())) rows.add(row);
                }
            }
            rows.sort((a, b) -> {
                int date = ((String)b.get("createdAt")).compareTo((String)a.get("createdAt"));
                return date != 0 ? date : Integer.compare((Integer)b.get("id"), (Integer)a.get("id"));
            });
            int end = Math.min(rows.size(), offset + PAGE_SIZE);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("items", offset < rows.size() ? rows.subList(offset, end) : Collections.emptyList());
            response.put("total", rows.size());
            response.put("hasMore", end < rows.size());
            return response;
        });
    }

    @Override public boolean markRead(User user, String facilityUuid, int id) {
        return transaction(() -> {
            Alert alert = alerts.getAlert(id);
            if (resolve(alert, user, facilityUuid, registry()) == null) return false;
            AlertRecipient recipient = alert.getRecipient(user);
            if (!Boolean.TRUE.equals(recipient.getAlertRead())) {
                recipient.setAlertRead(true);
                recipient.setDateChanged(new Date());
                save(alert);
            }
            return true;
        });
    }

    private Map<String, Object> resolve(Alert alert, User user, String facilityUuid,
            Map<String, NotificationInboxType> types) {
        if (alert == null || user == null || facilityUuid == null || alert.getRecipient(user) == null
                || alert.getText() == null || !alert.getText().startsWith(PREFIX)) return null;
        String[] identity = alert.getText().substring(PREFIX.length()).split(":", -1);
        if (identity.length != 2 || !identity[1].matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        NotificationInboxType type = types.get(identity[0]);
        if (!authorized(user, type)) return null;
        Map<String, Object> content = type.resolve(identity[1], user, facilityUuid);
        if (content == null || !(content.get("title") instanceof String)
                || !(content.get("subtitle") instanceof String)) return null;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", alert.getAlertId());
        row.put("type", identity[0]);
        row.put("subjectUuid", identity[1]);
        row.put("createdAt", alert.getDateCreated().toInstant().toString());
        row.put("content", content);
        return row;
    }

    protected void save(Alert alert) {
        withPrivilege("Manage Alerts", () -> alerts.saveAlert(alert));
    }
    protected <T> T withPrivilege(String privilege, Supplier<T> operation) {
        boolean grant = !Context.hasPrivilege(privilege);
        if (grant) Context.addProxyPrivilege(privilege);
        try { return operation.get(); }
        finally { if (grant) Context.removeProxyPrivilege(privilege); }
    }
    private <T> T transaction(Supplier<T> operation) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> operation.get());
    }
    @Override public void onStartup() { }
    @Override public void onShutdown() { }
}
