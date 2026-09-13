package com.npucraft.farmguard.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class LanguagePack {

    private final String locale;
    private final String displayName;
    private final Map<String, String> messages;

    public LanguagePack(String locale, String displayName, Map<String, String> messages) {
        this.locale = locale == null ? "" : locale;
        this.displayName = displayName == null || displayName.isBlank() ? this.locale : displayName;
        this.messages = messages == null || messages.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(messages));
    }

    public String locale() {
        return locale;
    }

    public String displayName() {
        return displayName;
    }

    public String get(String key) {
        return messages.get(key);
    }

    public boolean has(String key) {
        return messages.containsKey(key);
    }

    public Set<String> keys() {
        return messages.keySet();
    }

    public Map<String, String> messages() {
        return messages;
    }
}
