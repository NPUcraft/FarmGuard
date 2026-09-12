package dev.farmguard.incident;

import dev.farmguard.model.LagIncident;
import dev.farmguard.util.Quarantine;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class HistoryStore {

    private final File file;
    private final Logger logger;

    public HistoryStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public List<LagIncident> load() {
        if (!file.exists()) {
            return List.of();
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<LagIncident> incidents = new ArrayList<>();
            List<?> raw = yaml.getList("incidents");
            if (raw == null) {
                if (file.length() > 0 && looksCorrupt()) {
                    quarantine("missing incidents list");
                    return List.of();
                }
                return List.of();
            }
            for (Object entry : raw) {
                if (!(entry instanceof ConfigurationSection) && !(entry instanceof java.util.Map<?, ?>)) {
                    continue;
                }
                try {
                    if (entry instanceof ConfigurationSection section) {
                        incidents.add(fromSection(section));
                    } else {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> map = (java.util.Map<String, Object>) entry;
                        incidents.add(fromMap(map));
                    }
                } catch (RuntimeException exception) {
                    logger.warning("[FarmGuard] Skipping a corrupt incident record: " + exception.getMessage());
                }
            }
            return incidents;
        } catch (RuntimeException exception) {
            quarantine(exception.getMessage());
            return List.of();
        }
    }

    private boolean looksCorrupt() {
        try {
            String text = java.nio.file.Files.readString(file.toPath());
            return text.contains("\0") || (!text.contains("incidents") && !text.isBlank());
        } catch (IOException ignored) {
            return true;
        }
    }

    private void quarantine(String reason) {
        logger.warning("[FarmGuard] Incident history is unreadable (" + reason + "). The file will be ignored and renamed.");
        Quarantine.move(file, logger, "incidents.yml");
    }

    public void save(List<LagIncident> incidents) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<java.util.Map<String, Object>> serialized = new ArrayList<>();
        for (LagIncident incident : incidents) {
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", incident.id());
            map.put("start", incident.startMs());
            if (incident.endMs() != null) {
                map.put("end", incident.endMs());
            }
            map.put("peak-mspt", incident.peakMspt());
            map.put("lowest-tps", incident.lowestTps());
            map.put("suspects", incident.topSuspects());
            map.put("actions", incident.protectionActions());
            map.put("recovered", incident.recovered());
            serialized.add(map);
        }
        yaml.set("incidents", serialized);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("[FarmGuard] Could not create history directory: " + parent.getAbsolutePath());
            }
            yaml.save(file);
        } catch (IOException exception) {
            logger.warning("[FarmGuard] Failed to save incident history: " + exception.getMessage());
        }
    }

    private static LagIncident fromSection(ConfigurationSection section) {
        return new LagIncident(
                section.getString("id", "unknown"),
                section.getLong("start"),
                section.contains("end") ? section.getLong("end") : null,
                section.getDouble("peak-mspt"),
                section.getDouble("lowest-tps"),
                section.getStringList("suspects"),
                section.getStringList("actions"),
                section.getBoolean("recovered")
        );
    }

    private static LagIncident fromMap(java.util.Map<String, Object> map) {
        Object end = map.get("end");
        return new LagIncident(
                String.valueOf(map.getOrDefault("id", "unknown")),
                asLong(map.get("start")),
                end == null ? null : asLong(end),
                asDouble(map.get("peak-mspt")),
                asDouble(map.get("lowest-tps")),
                asStringList(map.get("suspects")),
                asStringList(map.get("actions")),
                Boolean.parseBoolean(String.valueOf(map.getOrDefault("recovered", false)))
        );
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }
}
