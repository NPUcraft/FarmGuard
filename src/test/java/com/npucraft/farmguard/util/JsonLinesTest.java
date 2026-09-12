package com.npucraft.farmguard.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonLinesTest {

    @Test
    void escapesQuotesBackslashNewlinesAndControls() {
        String json = JsonLines.object(Map.of(
                "text", "quote \" slash \\ newline \n tab \t ctrl " + '\u0001'
        ));
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\t"));
        assertTrue(json.contains("\\u0001"));
        assertFalse(json.contains("\n"));
        assertEquals("\"hi\\\"there\"", JsonLines.escape("hi\"there"));
    }

    @Test
    void encodesNumbersBooleansEnumsAndStringArrays() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("ok", true);
        fields.put("count", 12);
        fields.put("mspt", 18.40);
        fields.put("level", com.npucraft.farmguard.model.RiskLevel.HIGH);
        fields.put("reasons", List.of("EXCESSIVE_HOPPERS", "SUSTAINED_LOAD"));
        String json = JsonLines.object(fields);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("\"count\":12"));
        assertTrue(json.contains("\"mspt\":18.4"));
        assertTrue(json.contains("\"level\":\"HIGH\""));
        assertTrue(json.contains("[\"EXCESSIVE_HOPPERS\",\"SUSTAINED_LOAD\"]"));
        assertFalse(json.contains("NaN"));
    }

    @Test
    void skipsNullFieldsAndKeepsEmptyObjectValid() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("keep", "yes");
        fields.put("drop", null);
        assertEquals("{\"keep\":\"yes\"}", JsonLines.object(fields));
        assertEquals("{}", JsonLines.object(Map.of()));
    }
}
