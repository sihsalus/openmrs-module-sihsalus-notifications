package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.openmrs.module.sihsalusnotifications.api.NotificationEvent;

public class NotificationJsonWriterTest {

    @Test
    public void serializesPayloadButNeverAuthorizationMetadata() throws Exception {
        NotificationEvent event = new NotificationEvent(
                "11111111-1111-4111-8111-111111111111",
                "queue",
                "QUEUE_UPDATED",
                "{\"queueUuid\":\"abc\",\"count\":2}",
                "22222222-2222-4222-8222-222222222222",
                "View Queue",
                1_788_304_400_000L);

        JsonNode result = new ObjectMapper().readTree(new NotificationJsonWriter().write(event));

        assertEquals("queue", result.get("topic").asText());
        assertEquals("QUEUE_UPDATED", result.get("type").asText());
        assertEquals("abc", result.get("payload").get("queueUuid").asText());
        assertEquals(2, result.get("payload").get("count").asInt());
        assertEquals("2026-09-01T23:13:20Z", result.get("timestamp").asText());
        assertFalse(result.has("recipientUserUuid"));
        assertFalse(result.has("requiredPrivilege"));
    }
}
