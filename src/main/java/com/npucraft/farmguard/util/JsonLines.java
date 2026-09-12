package com.npucraft.farmguard.util;

import java.util.Collection;
import java.util.Map;

/**
 * Tiny JSON Lines encoder for Debug Log. Supports the FarmGuard diagnostic
 * payload types only: string, number, boolean, enum, string/enum arrays, and
 * nested maps of those values. Not a general JSON library.
 */
public final class JsonLines {

    private JsonLines() {
    }

    public static String object(Map<String, ?> fields) {
        StringBuilder builder = new StringBuilder(192);
        writeObject(builder, fields);
        return builder.toString();
    }

    public static String escape(String value) {
        StringBuilder builder = new StringBuilder(value == null ? 2 : value.length() + 8);
        quote(builder, value);
        return builder.toString();
    }

    static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String text) {
            quote(builder, text);
            return;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            builder.append(value);
            return;
        }
        if (value instanceof Double || value instanceof Float) {
            writeNumber(builder, ((Number) value).doubleValue());
            return;
        }
        if (value instanceof Number number) {
            writeNumber(builder, number.doubleValue());
            return;
        }
        if (value instanceof Enum<?> enumerated) {
            quote(builder, enumerated.name());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeObject(builder, map);
            return;
        }
        if (value instanceof Collection<?> collection) {
            writeArray(builder, collection);
            return;
        }
        if (value.getClass().isArray()) {
            throw new IllegalArgumentException("Java arrays are not supported; use Collection");
        }
        quote(builder, String.valueOf(value));
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> fields) {
        builder.append('{');
        boolean first = true;
        if (fields != null) {
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (!first) {
                    builder.append(',');
                }
                first = false;
                quote(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
        }
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, Collection<?> values) {
        builder.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            writeValue(builder, value);
        }
        builder.append(']');
    }

    private static void writeNumber(StringBuilder builder, double value) {
        if (!Double.isFinite(value)) {
            builder.append("0");
            return;
        }
        builder.append(new java.math.BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString());
    }

    private static void quote(StringBuilder builder, String value) {
        builder.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\b' -> builder.append("\\b");
                    case '\f' -> builder.append("\\f");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (ch < 0x20) {
                            builder.append("\\u");
                            String hex = Integer.toHexString(ch);
                            builder.append("0000", 0, 4 - hex.length());
                            builder.append(hex);
                        } else {
                            builder.append(ch);
                        }
                    }
                }
            }
        }
        builder.append('"');
    }
}
