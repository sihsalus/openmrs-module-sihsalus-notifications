package org.openmrs.module.sihsalusnotifications.api;

public final class NotificationRequest {

    private final String topic;

    private final String type;

    private final String payloadJson;

    private final String recipientUserUuid;

    private final String requiredPrivilege;

    private NotificationRequest(String topic, String type, String payloadJson,
            String recipientUserUuid, String requiredPrivilege) {
        this.topic = topic;
        this.type = type;
        this.payloadJson = payloadJson;
        this.recipientUserUuid = recipientUserUuid;
        this.requiredPrivilege = requiredPrivilege;
    }

    public static NotificationRequest forUser(String recipientUserUuid, String topic,
            String type, String payloadJson) {
        return new NotificationRequest(topic, type, payloadJson, recipientUserUuid, null);
    }

    public static NotificationRequest forUserWithPrivilege(String recipientUserUuid,
            String topic, String type, String payloadJson, String requiredPrivilege) {
        return new NotificationRequest(topic, type, payloadJson, recipientUserUuid, requiredPrivilege);
    }

    public static NotificationRequest forPrivilege(String topic, String type,
            String payloadJson, String requiredPrivilege) {
        return new NotificationRequest(topic, type, payloadJson, null, requiredPrivilege);
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
}
