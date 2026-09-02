package org.openmrs.module.sihsalusnotifications.web;

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;

final class NotificationJsonWriter {

    private final ObjectMapper objectMapper;

    NotificationJsonWriter() {
        this(new ObjectMapper());
    }

    NotificationJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(NotificationEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayloadJson());
            ObjectNode message = objectMapper.createObjectNode();
            message.put("id", event.getId());
            message.put("topic", event.getTopic());
            message.put("type", event.getType());
            message.put("timestamp", Instant.ofEpochMilli(event.getCreatedAtEpochMillis()).toString());
            message.set("payload", payload);
            return objectMapper.writeValueAsString(message);
        } catch (IOException exception) {
            throw new IllegalStateException("A validated notification could not be serialized", exception);
        }
    }
}
