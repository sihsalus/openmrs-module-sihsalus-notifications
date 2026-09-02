package org.openmrs.module.sihsalusnotifications.api;

public final class NotificationEvent {

    private final String id;

    private final String topic;

    private final String type;

    private final String payloadJson;

    private final String recipientUserUuid;

    private final String requiredPrivilege;

    private final long createdAtEpochMillis;

    public NotificationEvent(String id, String topic, String type, String payloadJson,
            String recipientUserUuid, String requiredPrivilege, long createdAtEpochMillis) {
        this.id = id;
        this.topic = topic;
        this.type = type;
        this.payloadJson = payloadJson;
        this.recipientUserUuid = recipientUserUuid;
        this.requiredPrivilege = requiredPrivilege;
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public String getId() {
        return id;
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

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }
}
