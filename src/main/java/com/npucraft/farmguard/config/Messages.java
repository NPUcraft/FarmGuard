package dev.farmguard.config;

import dev.farmguard.util.Placeholders;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Map<String, String> values = new HashMap<>();
    private String prefix = "<gray>[<green>FarmGuard</green>]</gray> ";

    public void load(File file, Logger logger) {
        load(file, logger, null);
    }

    /**
     * Loads operator messages. Bundled jar keys fill gaps so upgrades do not print raw keys.
     * Customized existing keys are never overwritten.
     */
    public int load(File file, Logger logger, YamlConfiguration bundled) {
        values.clear();
        putFlat(bundled);
        int added = 0;
        try {
            YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
            if (bundled != null) {
                added = mergeMissing(yaml, bundled);
                if (added > 0) {
                    yaml.save(file);
                    logger.info("[FarmGuard] Added " + added + " missing message key(s) to messages.yml");
                }
            }
            putFlat(yaml);
            prefix = values.getOrDefault("prefix", prefix);
        } catch (IOException | RuntimeException exception) {
            logger.warning("[FarmGuard] Failed to read messages.yml, using built-in Chinese defaults: " + exception.getMessage());
        }
        return added;
    }

    static int mergeMissing(YamlConfiguration user, YamlConfiguration bundled) {
        int added = 0;
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

    private void putFlat(YamlConfiguration yaml) {
        if (yaml == null) {
            return;
        }
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key)) {
                continue;
            }
            String value = yaml.getString(key);
            if (value != null) {
                values.put(key, value);
            }
        }
    }

    public String raw(String key) {
        return values.getOrDefault(key, key);
    }

    public Component component(String key, Map<String, String> placeholders) {
        String body = Placeholders.apply(raw(key), placeholders);
        String withPrefix = key.equals("prefix") ? body : prefix + body;
        try {
            return MINI.deserialize(withPrefix);
        } catch (RuntimeException exception) {
            return Component.text(stripTags(withPrefix));
        }
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(component(key, placeholders));
    }

    public Component plainPrefixed(String text) {
        try {
            return MINI.deserialize(prefix + text);
        } catch (RuntimeException exception) {
            return Component.text(stripTags(prefix) + text);
        }
    }

    private static String stripTags(String input) {
        return input.replaceAll("<[^>]+>", "");
    }
}
