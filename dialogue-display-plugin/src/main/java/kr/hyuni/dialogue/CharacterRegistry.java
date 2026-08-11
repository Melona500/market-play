package kr.hyuni.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class CharacterRegistry {
    private static final List<String> BASE_EXPRESSIONS =
            List.of("NEUTRAL", "HAPPY", "SAD", "ANGRY", "SURPRISED");

    private final String rawJson;
    private final int schemaVersion;
    private final Map<String, CharacterSpec> characters = new LinkedHashMap<>();
    private final Map<String, PortraitSpec> portraits = new LinkedHashMap<>();
    private final Map<String, Integer> expressionColumns = new LinkedHashMap<>();
    private final Map<String, String> expressionLabels = new LinkedHashMap<>();
    private final Map<String, Integer> legacyGlyphs = new LinkedHashMap<>();
    private final YamlConfiguration manifest;

    private CharacterRegistry(String rawJson, YamlConfiguration manifest) {
        this.rawJson = rawJson;
        this.manifest = manifest;
        this.schemaVersion = manifest.getInt("schemaVersion", 1);

        ConfigurationSection columns = manifest.getConfigurationSection("expressionColumns");
        if (columns != null) for (String key : columns.getKeys(false))
            expressionColumns.put(key, columns.getInt(key));

        ConfigurationSection labels = manifest.getConfigurationSection("expressionLabels");
        if (labels != null) for (String key : labels.getKeys(false))
            expressionLabels.put(key, labels.getString(key, key));

        ConfigurationSection legacy = manifest.getConfigurationSection("legacyPortraits");
        if (legacy != null) for (String portrait : legacy.getKeys(false))
            legacyGlyphs.put(portrait, parseGlyph(legacy.getString(portrait + ".glyph", "")));

        for (Map<?, ?> entry : manifest.getMapList("characters")) {
            CharacterSpec spec = CharacterSpec.from(entry);
            if (spec.id().isBlank()) continue;
            characters.put(spec.id(), spec);
            if (spec.family().equals("rpg") || spec.family().equals("village")) {
                for (var row : spec.rows().entrySet()) {
                    String portrait = row.getKey() + "_" + spec.suffix();
                    portraits.put(portrait, new PortraitSpec(spec, row.getKey(), row.getValue()));
                }
            } else {
                String portrait = spec.portrait().isBlank() ? spec.id() : spec.portrait();
                portraits.put(portrait, new PortraitSpec(spec, "NONE", spec.neutralIndex()));
            }
        }
    }

    static CharacterRegistry load(JavaPlugin plugin) {
        try {
            var stream = Objects.requireNonNull(plugin.getResource("rpgmaker-character-manifest.json"));
            String json;
            try (stream) {
                json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(json));
            CharacterRegistry registry = new CharacterRegistry(json, yaml);
            plugin.getLogger().info("Loaded RPGMaker character manifest v" + registry.schemaVersion
                    + " (" + registry.characters.size() + " characters)");
            return registry;
        } catch (Exception error) {
            plugin.getLogger().warning("Character manifest unavailable; built-in compatibility mapping will be used: "
                    + error.getMessage());
            return null;
        }
    }

    String rawJson() {
        return rawJson;
    }

    int schemaVersion() {
        return schemaVersion;
    }

    List<String> characterIds() {
        return List.copyOf(characters.keySet());
    }

    String characterLabel(String character) {
        CharacterSpec spec = characters.get(character);
        return spec == null ? character : spec.label();
    }

    boolean hasGender(String character) {
        CharacterSpec spec = characters.get(character);
        return spec != null && spec.genders().contains("MALE") && spec.genders().contains("FEMALE");
    }

    String resolvePortrait(String character, String gender) {
        CharacterSpec spec = characters.get(character);
        if (spec == null) return null;
        if (!spec.family().equals("rpg") && !spec.family().equals("village"))
            return spec.portrait().isBlank() ? spec.id() : spec.portrait();
        String resolvedGender = "FEMALE".equals(gender) ? "FEMALE" : "MALE";
        return resolvedGender + "_" + spec.suffix();
    }

    String characterFromPortrait(String portrait) {
        PortraitSpec variant = portraits.get(portrait);
        if (variant != null) return variant.character().id();
        return legacyGlyphs.containsKey(portrait) && characters.containsKey(portrait) ? portrait : null;
    }

    String genderFromPortrait(String portrait) {
        PortraitSpec variant = portraits.get(portrait);
        return variant == null ? (portrait != null && portrait.startsWith("FEMALE_") ? "FEMALE" : "MALE")
                : variant.gender();
    }

    List<String> availableExpressions(String portrait) {
        PortraitSpec variant = portraits.get(portrait);
        if (variant != null) {
            if (!variant.character().expressions().isEmpty()) return variant.character().expressions();
            ArrayList<String> result = new ArrayList<>(BASE_EXPRESSIONS);
            if ("FEMALE".equals(variant.gender())) result.add("EMBARRASSED");
            return result;
        }
        if (legacyGlyphs.containsKey(portrait)) {
            ArrayList<String> result = new ArrayList<>(BASE_EXPRESSIONS);
            if (List.of("MAGE", "ARCHER").contains(portrait)) result.add("EMBARRASSED");
            return result;
        }
        return BASE_EXPRESSIONS;
    }

    String expressionLabel(String expression) {
        return expressionLabels.getOrDefault(expression, expression);
    }

    String glyph(String portrait, String expression) {
        PortraitSpec variant = portraits.get(portrait);
        if (variant == null) {
            Integer legacy = legacyGlyphs.get(portrait);
            return legacy == null || legacy <= 0 ? null : Character.toString(legacy);
        }
        CharacterSpec character = variant.character();
        if (character.family().equals("fixed") || character.family().equals("monster")) {
            int glyph = parseGlyph(character.glyph());
            return glyph <= 0 ? null : Character.toString(glyph);
        }

        List<String> allowed = availableExpressions(portrait);
        String resolved = allowed.contains(expression) ? expression : "NEUTRAL";
        int row = variant.row();
        if (resolved.equals("NEUTRAL")) {
            int base = familyGlyph(character.family(), "neutralGlyphStart");
            return base <= 0 ? null : Character.toString(base + row);
        }

        int column = expressionColumns.getOrDefault(resolved, 0);
        int rowsPerSheet = manifest.getInt("families." + character.family() + ".rowsPerEmotionSheet", 6);
        List<String> starts = manifest.getStringList("families." + character.family() + ".emotionGlyphStarts");
        if (starts.isEmpty()) return null;
        int sheet = Math.max(0, Math.min(starts.size() - 1, row / rowsPerSheet));
        int base = parseGlyph(starts.get(sheet));
        return Character.toString(base + (row % rowsPerSheet) * 5 + column);
    }

    private int familyGlyph(String family, String key) {
        return parseGlyph(manifest.getString("families." + family + "." + key, ""));
    }

    private static int parseGlyph(String value) {
        if (value == null || value.isBlank()) return -1;
        try {
            return Integer.parseInt(value.replace("U+", ""), 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record PortraitSpec(CharacterSpec character, String gender, int row) {}

    private record CharacterSpec(
            String id,
            String label,
            String family,
            String suffix,
            String portrait,
            String sheet,
            String glyph,
            List<String> genders,
            List<String> expressions,
            Map<String, Integer> rows,
            int neutralIndex
    ) {
        static CharacterSpec from(Map<?, ?> raw) {
            String id = text(raw.get("id")).toUpperCase(Locale.ROOT);
            Map<String, Integer> rows = new LinkedHashMap<>();
            if (raw.get("rows") instanceof Map<?, ?> rowMap) {
                rowMap.forEach((key, value) -> {
                    if (value instanceof Number number)
                        rows.put(text(key).toUpperCase(Locale.ROOT), number.intValue());
                });
            }
            return new CharacterSpec(
                    id,
                    text(raw.get("label")),
                    text(raw.get("family")),
                    text(raw.get("suffix")).toUpperCase(Locale.ROOT),
                    text(raw.get("portrait")).toUpperCase(Locale.ROOT),
                    text(raw.get("sheet")),
                    text(raw.get("glyph")),
                    strings(raw.get("genders")),
                    strings(raw.get("expressions")),
                    rows,
                    raw.get("neutralIndex") instanceof Number number ? number.intValue() : 0
            );
        }

        private static String text(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static List<String> strings(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().map(CharacterSpec::text)
                    .map(item -> item.toUpperCase(Locale.ROOT)).toList();
        }
    }
}
