package dev.farmguard.config;

import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.OperatingMode;
import dev.farmguard.util.Quarantine;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

public final class StateStore {

    private final File file;
    private final Logger logger;

    public StateStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public record LoadedState(OperatingMode mode, List<ChunkKey> chunks, List<String> clusters) {
    }

    public LoadedState load(OperatingMode fallbackMode) {
        if (!file.exists()) {
            return new LoadedState(fallbackMode, List.of(), List.of());
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            if (file.length() > 0 && !yaml.contains("mode") && !yaml.contains("chunks")) {
                logger.warning("[FarmGuard] state.yml is missing expected keys and will be treated as corrupt.");
                quarantine();
                return new LoadedState(fallbackMode, List.of(), List.of());
            }
            OperatingMode mode = OperatingMode.parse(yaml.getString("mode"), fallbackMode);
            List<ChunkKey> chunks = new ArrayList<>();
            for (String raw : yaml.getStringList("chunks")) {
                ChunkKey key = Whitelist.parseChunk(raw);
                if (key != null) {
                    chunks.add(key);
                }
            }
            return new LoadedState(mode, chunks, yaml.getStringList("clusters"));
        } catch (RuntimeException exception) {
            logger.warning("[FarmGuard] state.yml is unreadable, using safe defaults: " + exception.getMessage());
            quarantine();
            return new LoadedState(fallbackMode, List.of(), List.of());
        }
    }

    private void quarantine() {
        Quarantine.move(file, logger, "state.yml");
    }

    public void save(OperatingMode mode, Whitelist whitelist) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mode", mode.name());
        List<String> chunks = new ArrayList<>();
        for (ChunkKey key : whitelist.chunks()) {
            chunks.add(Whitelist.formatChunk(key));
        }
        yaml.set("chunks", chunks);
        yaml.set("clusters", List.copyOf(whitelist.clusterIds()));
        try {
            yaml.save(file);
        } catch (IOException exception) {
            logger.warning("[FarmGuard] Failed to save state.yml: " + exception.getMessage());
        }
    }
}
