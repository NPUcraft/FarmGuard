package com.npucraft.farmguard.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

final class YamlMessages {

    private YamlMessages() {
    }

    static Map<String, String> flatten(YamlConfiguration yaml) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (yaml == null) {
            return out;
        }
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key) || key.equals("_meta") || key.startsWith("_meta.")) {
                continue;
            }
            String value = yaml.getString(key);
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    static int mergeMissing(YamlConfiguration user, YamlConfiguration bundled) {
        int added = 0;
        if (user == null || bundled == null) {
            return 0;
        }
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) {
                continue;
            }
            if (!user.contains(key)) {
                user.set(key, bundled.get(key));
                added++;
            }
        }
        return added;
    }

    static String metaName(YamlConfiguration yaml, String fallback) {
        if (yaml == null) {
            return fallback;
        }
        String name = yaml.getString("_meta.name");
        return name == null || name.isBlank() ? fallback : name;
    }

    static String metaLocale(YamlConfiguration yaml, String fallback) {
        if (yaml == null) {
            return fallback;
        }
        String locale = yaml.getString("_meta.locale");
        String normalized = LocaleIds.tryNormalize(locale);
        return normalized == null ? fallback : normalized;
    }
}
