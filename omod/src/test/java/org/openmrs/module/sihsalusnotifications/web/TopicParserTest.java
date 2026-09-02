package org.openmrs.module.sihsalusnotifications.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class TopicParserTest {

    private final TopicParser parser = new TopicParser();

    @Test
    public void defaultsToSystemAndCombinesRepeatedCommaSeparatedValues() {
        assertEquals("system", parser.parse((String[]) null).iterator().next());

        Map<String, List<String>> parameters = new HashMap<String, List<String>>();
        List<String> values = new ArrayList<String>();
        values.add("queue, laboratory");
        values.add("system,queue");
        parameters.put("topics", values);

        Set<String> topics = parser.parse(parameters);
        assertEquals(3, topics.size());
        assertEquals("queue", topics.iterator().next());
    }

    @Test
    public void rejectsControlCharactersAndUnboundedTopicLists() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(new String[] { "queue\ndata: secret" }));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(new String[] {
                    "t0,t1,t2,t3,t4,t5,t6,t7,t8,t9,t10,t11,t12,t13,t14,t15,t16"
                }));
    }
}
