package kr.hyuni.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WebJson {
    private WebJson() {}

    static Map<String, Object> parseObject(String input) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(input));
        return sectionToMap(yaml);
    }

    static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) result.put(key, sectionToMap(child));
            else if (value instanceof List<?> list) result.put(key, normalizeList(list));
            else result.put(key, value);
        }
        return result;
    }

    private static List<Object> normalizeList(List<?> list) {
        ArrayList<Object> result = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof ConfigurationSection child) result.add(sectionToMap(child));
            else if (value instanceof Map<?, ?> map) result.add(normalizeMap(map));
            else if (value instanceof List<?> nested) result.add(normalizeList(nested));
            else result.add(value);
        }
        return result;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (value instanceof Map<?, ?> child) result.put(String.valueOf(key), normalizeMap(child));
            else if (value instanceof List<?> list) result.put(String.valueOf(key), normalizeList(list));
            else result.put(String.valueOf(key), value);
        });
        return result;
    }

    static String stringify(Object value) {
        StringBuilder result = new StringBuilder();
        append(result, value);
        return result.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            quote(out, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else {
            quote(out, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int offset = 0; offset < value.length(); offset++) {
            char character = value.charAt(offset);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) out.append(String.format("\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        out.append('"');
    }
}
