package org.openmrs.module.sihsalusnotifications.web;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.openmrs.module.sihsalusnotifications.NotificationConstants;

final class TopicParser {

    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");

    Set<String> parse(String[] values) {
        Set<String> topics = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                addValue(topics, value);
            }
        }
        if (topics.isEmpty()) {
            topics.add("system");
        }
        return Collections.unmodifiableSet(topics);
    }

    Set<String> parse(Map<String, List<String>> parameters) {
        List<String> values = parameters == null ? null : parameters.get("topics");
        return parse(values == null ? null : values.toArray(new String[values.size()]));
    }

    private void addValue(Set<String> topics, String value) {
        if (value == null) {
            return;
        }
        for (String candidate : value.split(",")) {
            String topic = candidate.trim();
            if (topic.isEmpty()) {
                continue;
            }
            if (!TOPIC.matcher(topic).matches()) {
                throw new IllegalArgumentException("Invalid notification topic");
            }
            topics.add(topic);
            if (topics.size() > NotificationConstants.MAX_TOPICS_PER_SUBSCRIPTION) {
                throw new IllegalArgumentException("Too many notification topics");
            }
        }
    }
}
