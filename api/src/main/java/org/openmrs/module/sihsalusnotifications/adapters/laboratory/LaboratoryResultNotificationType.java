package org.openmrs.module.sihsalusnotifications.adapters.laboratory;

import java.util.*;
import org.openmrs.*;
import org.openmrs.api.OrderService;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.sihsalusnotifications.api.FacilityLocationScope;
import org.openmrs.module.sihsalusnotifications.api.NotificationInboxType;

/** Laboratory owns eligibility, requester selection and clinical resource authorization. */
public class LaboratoryResultNotificationType implements NotificationInboxType {
    public static final String NAME = "laboratory-result-ready";
    private static final Set<String> PRIVILEGES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "app:hoja.clinica.ordenes", "Get Orders", "Get Patients", "Get Observations")));
    private OrderService orders;
    private UserService users;
    public void setOrders(OrderService value) { orders = value; }
    public void setUsers(UserService value) { users = value; }
    @Override public String getName() { return NAME; }
    @Override public Set<String> getReadPrivileges() { return PRIVILEGES; }

    @Override public Delivery recipient(String subjectUuid) {
        Order order = orders.getOrderByUuid(subjectUuid);
        if (!eligible(order)) return null;
        List<User> candidates = requesterAccounts(order.getOrderer().getPerson());
        if (candidates.size() != 1) return null;
        return new Delivery(candidates.get(0), FacilityLocationScope.facilityUuid(order.getEncounter().getLocation()));
    }

    protected List<User> requesterAccounts(Person person) {
        boolean grant = !Context.hasPrivilege("Get Users");
        if (grant) Context.addProxyPrivilege("Get Users");
        try { return users.getUsersByPerson(person, false); }
        finally { if (grant) Context.removeProxyPrivilege("Get Users"); }
    }

    @Override public Map<String, Object> resolve(String subjectUuid, User user, String facilityUuid) {
        Order order = orders.getOrderByUuid(subjectUuid);
        if (!eligible(order) || user.getPerson() == null || !user.getPerson().equals(order.getOrderer().getPerson())
                || !facilityUuid.equals(FacilityLocationScope.facilityUuid(order.getEncounter().getLocation()))) return null;
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("title", order.getPatient().getPersonName().getFullName());
        content.put("subtitle", order.getConcept().getName().getName());
        content.put("patientUuid", order.getPatient().getUuid());
        return content;
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

}
