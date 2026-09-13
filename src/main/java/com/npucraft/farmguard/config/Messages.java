package com.npucraft.farmguard.config;

import com.npucraft.farmguard.i18n.MessageService;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * Compatibility facade over {@link MessageService}. New code should use MessageService.
 */
public final class Messages {

    private final MessageService service;

    public Messages(MessageService service) {
        this.service = service;
    }

    public String raw(String key) {
        return service.language().raw(key);
    }

    public Component component(String key, Map<String, String> placeholders) {
        return service.component(key, placeholders);
    }

    public void send(CommandSender sender, String key) {
        service.send(sender, key);
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        service.send(sender, key, placeholders);
    }

    public Component plainPrefixed(String text) {
        return service.prefixed(service.deserialize(text));
    }
}
