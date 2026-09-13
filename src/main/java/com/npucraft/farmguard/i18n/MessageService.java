package com.npucraft.farmguard.i18n;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MessageService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final LanguageManager language;

    public MessageService(LanguageManager language) {
        this.language = language;
    }

    public LanguageManager language() {
        return language;
    }

    public String prefixRaw() {
        return language.raw("prefix");
    }

    public Component prefix() {
        return deserialize(prefixRaw());
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> placeholders) {
        return prefixed(deserialize(language.raw(key, placeholders)));
    }

    public Component prefixed(Component body) {
        return prefix().append(body);
    }

    public Component deserialize(String mini) {
        if (mini == null || mini.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(mini);
        } catch (RuntimeException exception) {
            return Component.text(stripTags(mini));
        }
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sendComponent(sender, component(key, placeholders));
    }

    public void sendBody(CommandSender sender, String key, Map<String, String> placeholders) {
        sendComponent(sender, deserialize(language.raw(key, placeholders)));
    }

    public void sendComponent(CommandSender sender, Component component) {
        if (sender == null || component == null) {
            return;
        }
        if (sender instanceof Player) {
            sender.sendMessage(component);
        } else {
            sender.sendMessage(PLAIN.serialize(component));
        }
    }

    public String plain(String key) {
        return plain(key, Map.of());
    }

    public String plain(String key, Map<String, String> placeholders) {
        return PLAIN.serialize(component(key, placeholders));
    }

    public String plainBody(String key, Map<String, String> placeholders) {
        return PLAIN.serialize(deserialize(language.raw(key, placeholders)));
    }

    public static String stripTags(String input) {
        return input == null ? "" : input.replaceAll("<[^>]+>", "");
    }

    public static String plainText(Component component) {
        return PLAIN.serialize(component == null ? Component.empty() : component);
    }
}
