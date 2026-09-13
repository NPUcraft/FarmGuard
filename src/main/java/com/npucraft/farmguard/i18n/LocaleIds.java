package com.npucraft.farmguard.i18n;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes administrator language tags. Business logic never sees raw input.
 */
public final class LocaleIds {

    public static final String ZH_CN = "zh_CN";
    public static final String EN_US = "en_US";
    public static final String CANONICAL = ZH_CN;

    private static final Pattern TAG = Pattern.compile("^([A-Za-z]{2})(?:[_-]([A-Za-z]{2}))?$");

    private LocaleIds() {
    }

    public static String canonicalize(String raw) {
        String normalized = tryNormalize(raw);
        return normalized == null ? CANONICAL : normalized;
    }

    public static String tryNormalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher matcher = TAG.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        String language = matcher.group(1).toLowerCase(Locale.ROOT);
        String region = matcher.group(2) == null ? "" : matcher.group(2).toUpperCase(Locale.ROOT);
        if (language.equals("zh") && (region.isEmpty() || region.equals("CN") || region.equals("HANS"))) {
            return ZH_CN;
        }
        if (language.equals("en") && (region.isEmpty() || region.equals("US"))) {
            return EN_US;
        }
        if (region.isEmpty()) {
            return language;
        }
        return language + "_" + region;
    }

    public static boolean isWellFormed(String raw) {
        return tryNormalize(raw) != null;
    }

    public static String kebab(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
