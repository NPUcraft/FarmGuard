package com.npucraft.farmguard.debug;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonlFileGate {

    private JsonlFileGate() {
    }

    static List<String> validate(Path path) throws Exception {
        List<String> errors = new ArrayList<>();
        if (path == null || !Files.exists(path)) {
            errors.add("missing " + path);
            return errors;
        }
        byte[] raw = Files.readAllBytes(path);
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            errors.add(path.getFileName() + " is empty");
            return errors;
        }
        String[] lines = text.split("\\R", -1);
        int objects = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            Object parsed;
            try {
                parsed = MiniJson.parse(line);
            } catch (RuntimeException exception) {
                errors.add(path.getFileName() + ":" + lineNo + " JSON " + exception.getMessage());
                continue;
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                errors.add(path.getFileName() + ":" + lineNo + " not an object");
                continue;
            }
            objects++;
            Object schema = map.get("schema");
            if (!(schema instanceof Number number) || number.intValue() != 1) {
                errors.add(path.getFileName() + ":" + lineNo + " schema=" + schema);
            }
            Object session = map.get("session");
            if (!(session instanceof String textSession) || textSession.isBlank()) {
                errors.add(path.getFileName() + ":" + lineNo + " session empty");
            }
            Object type = map.get("type");
            if (!(type instanceof String typeName) || typeName.isBlank()) {
                errors.add(path.getFileName() + ":" + lineNo + " type empty");
            }
            Object ts = map.get("ts");
            if (!(ts instanceof String timestamp)) {
                errors.add(path.getFileName() + ":" + lineNo + " ts missing");
            } else {
                try {
                    OffsetDateTime.parse(timestamp);
                } catch (RuntimeException exception) {
                    errors.add(path.getFileName() + ":" + lineNo + " ts not ISO-8601 offset: " + timestamp);
                }
            }
        }
        if (objects == 0) {
            errors.add(path.getFileName() + " contained no JSON objects");
        }
        return errors;
    }

    static int debugLogThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && "FarmGuard-DebugLog".equals(thread.getName())) {
                count++;
            }
        }
        return count;
    }

    private static final class MiniJson {
        private final String text;
        private int index;

        private MiniJson(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            MiniJson parser = new MiniJson(text);
            Object value = parser.value();
            parser.skip();
            if (parser.index != parser.text.length()) {
                throw new IllegalArgumentException("trailing garbage at " + parser.index);
            }
            return value;
        }

        private Object value() {
            skip();
            if (index >= text.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char ch = text.charAt(index);
            if (ch == '{') {
                return object();
            }
            if (ch == '[') {
                return array();
            }
            if (ch == '"') {
                return string();
            }
            if (ch == 't' || ch == 'f') {
                return bool();
            }
            if (ch == 'n') {
                return nul();
            }
            return number();
        }

        private Map<String, Object> object() {
            expect('{');
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(':');
                map.put(key, value());
                skip();
                if (peek() == ',') {
                    index++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skip();
            if (peek() == ']') {
                index++;
                return values;
            }
            while (true) {
                values.add(value());
                skip();
                if (peek() == ',') {
                    index++;
                    continue;
                }
                expect(']');
                return values;
            }
        }

        private String string() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (index >= text.length()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char esc = text.charAt(index++);
                    builder.append(switch (esc) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> hex();
                        default -> throw new IllegalArgumentException("bad escape \\" + esc);
                    });
                    continue;
                }
                if (ch < 0x20) {
                    throw new IllegalArgumentException("unescaped control");
                }
                builder.append(ch);
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private char hex() {
            if (index + 4 > text.length()) {
                throw new IllegalArgumentException("bad unicode");
            }
            int value = Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            return (char) value;
        }

        private Object number() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (peek() == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String raw = text.substring(start, index);
            if (raw.indexOf('.') >= 0) {
                return Double.parseDouble(raw);
            }
            long value = Long.parseLong(raw);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
            return value;
        }

        private Boolean bool() {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad boolean");
        }

        private Object nul() {
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new IllegalArgumentException("bad null");
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private char peek() {
            return index < text.length() ? text.charAt(index) : 0;
        }

        private void expect(char ch) {
            if (peek() != ch) {
                throw new IllegalArgumentException("expected " + ch + " at " + index);
            }
            index++;
        }
    }
}
