package com.npucraft.farmguard.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes {@code language:} without rewriting the rest of config.yml comments.
 */
public final class ConfigLanguagePatch {

    private static final Pattern LANGUAGE_LINE = Pattern.compile("(?m)^language:\\s*.*$");
    private static final Pattern MODE_LINE = Pattern.compile("(?m)^mode:\\s*.*$");

    private ConfigLanguagePatch() {
    }

    public static boolean write(Path configFile, String locale, Logger logger) {
        if (configFile == null || locale == null || locale.isBlank()) {
            return false;
        }
        try {
            String text = Files.exists(configFile)
                    ? Files.readString(configFile, StandardCharsets.UTF_8)
                    : "";
            String line = "language: " + locale;
            Matcher existing = LANGUAGE_LINE.matcher(text);
            String next;
            if (existing.find()) {
                next = existing.replaceFirst(Matcher.quoteReplacement(line));
            } else {
                Matcher mode = MODE_LINE.matcher(text);
                if (mode.find()) {
                    next = mode.replaceFirst(Matcher.quoteReplacement(mode.group() + System.lineSeparator()
                            + System.lineSeparator()
                            + "# UI language. Default zh_CN. Switch with /fg language."
                            + System.lineSeparator()
                            + line));
                } else if (text.isBlank()) {
                    next = line + System.lineSeparator();
                } else {
                    next = text.stripTrailing() + System.lineSeparator() + line + System.lineSeparator();
                }
            }
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, next, StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            if (logger != null) {
                logger.warning("[FarmGuard] Could not persist language to config.yml: " + exception.getMessage());
            }
            return false;
        }
    }
}
