package org.openmrs.module.sihsalusnotifications.api;

public final class NotificationRequest {

    private final String topic;

    private final String type;

    private final String payloadJson;

    private final String recipientUserUuid;

    private final String requiredPrivilege;

    private final String scopeLocationUuid;

    private NotificationRequest(String topic, String type, String payloadJson,
            String recipientUserUuid, String requiredPrivilege, String scopeLocationUuid) {
        this.topic = topic;
        this.type = type;
        this.payloadJson = payloadJson;
        this.recipientUserUuid = recipientUserUuid;
        this.requiredPrivilege = requiredPrivilege;
        this.scopeLocationUuid = scopeLocationUuid;
    }

    public static NotificationRequest forUser(String recipientUserUuid, String topic,
            String type, String payloadJson) {
        return new NotificationRequest(topic, type, payloadJson, recipientUserUuid, null, null);
    }

    public static NotificationRequest forUserWithPrivilege(String recipientUserUuid,
            String topic, String type, String payloadJson, String requiredPrivilege) {
        return new NotificationRequest(topic, type, payloadJson, recipientUserUuid, requiredPrivilege, null);
    }

    public static NotificationRequest forPrivilege(String topic, String type,
            String payloadJson, String requiredPrivilege) {
        return new NotificationRequest(topic, type, payloadJson, null, requiredPrivilege, null);
    }

    public static NotificationRequest forPrivilegeAtLocation(String topic, String type,
            String payloadJson, String requiredPrivilege, String scopeLocationUuid) {
        if (scopeLocationUuid == null || scopeLocationUuid.trim().isEmpty()) {
            throw new IllegalArgumentException("Notification scope location UUID is required");
        }
        return new NotificationRequest(topic, type, payloadJson, null, requiredPrivilege,
                scopeLocationUuid);
    }

    public String getTopic() {
        return topic;
    }

    public String getType() {
        return type;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getRecipientUserUuid() {
        return recipientUserUuid;
    }

    public String getRequiredPrivilege() {
        return requiredPrivilege;
    }

    public String getScopeLocationUuid() {
        return scopeLocationUuid;
    }
}
