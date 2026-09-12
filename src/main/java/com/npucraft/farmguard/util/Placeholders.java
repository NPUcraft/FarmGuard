package com.npucraft.farmguard.util;

import java.util.Map;

public final class Placeholders {

    private Placeholders() {
    }

    public static String apply(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (values == null || values.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}
