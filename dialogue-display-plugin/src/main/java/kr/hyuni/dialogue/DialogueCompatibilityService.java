package kr.hyuni.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class DialogueCompatibilityService {
    private static final int MAXIMUM_PAGES = 30;
    private static final int MAXIMUM_LINES = 4;
    private static final int MAXIMUM_CHARACTERS_PER_LINE = 30;
    private static final int MAXIMUM_CHOICES = 8;
    private static final int MAXIMUM_TITLE_CHARACTERS = 60;
    private static final int MAXIMUM_SPEAKER_CHARACTERS = 10;
    private static final int MAXIMUM_CHOICE_CHARACTERS = 10;
    private static final int MAXIMUM_CHOICE_DEPTH = 16;

    private final JavaPlugin plugin;

    DialogueCompatibilityService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    List<Map<String, Object>> list(UUID owner) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(ownerRoot(owner));
        if (section == null) return List.of();
        return section.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).map(name -> {
            String path = ownerRoot(owner) + "." + name;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("title", plugin.getConfig().getString(path + ".title", name));
            item.put("revision", revision(path));
            item.put("pages", pageCount(path));
            return item;
        }).toList();
    }

    List<Map<String, Object>> listOwners() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("player-dialogues");
        if (section == null) return List.of();
        return section.getKeys(false).stream().map(key -> {
            try {
                UUID owner = UUID.fromString(key);
                String name = Bukkit.getOfflinePlayer(owner).getName();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ownerUuid", key);
                result.put("playerName", name == null ? key : name);
                result.put("dialogues", list(owner).size());
                return result;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }).filter(java.util.Objects::nonNull).sorted(Comparator.comparing(item -> String.valueOf(item.get("playerName")), String.CASE_INSENSITIVE_ORDER)).toList();
    }

    List<Map<String, Object>> listPublic() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("public-dialogues");
        if (section == null) return List.of();
        return section.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).map(name -> {
            String path = publicRoot(name);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("title", plugin.getConfig().getString(path + ".title", name));
            item.put("revision", revision(path));
            item.put("pages", pageCount(path));
            item.put("ownerUuid", plugin.getConfig().getString(path + ".public-owner", ""));
            item.put("publisher", plugin.getConfig().getString(path + ".public-by", "RPGMaker"));
            return item;
        }).toList();
    }

    DialogueDocument getPublic(String rawName) {
        String name = sanitize(rawName);
        String path = publicRoot(name);
        if (!plugin.getConfig().contains(path)) return null;
        Map<String, Object> data = new LinkedHashMap<>(readSection(path));
        data.remove("public-owner");
        data.remove("public-by");
        return new DialogueDocument(name, revision(path), data);
    }

    UUID publicOwner(String rawName) {
        String value = plugin.getConfig().getString(publicRoot(sanitize(rawName)) + ".public-owner", "");
        try { return value.isBlank() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    String publicPublisher(String rawName) {
        return plugin.getConfig().getString(publicRoot(sanitize(rawName)) + ".public-by", "RPGMaker");
    }

    SaveResult savePublic(UUID owner, String publisher, String rawName, String expectedRevision,
                          Map<String, Object> data) {
        String name = sanitize(rawName);
        String path = publicRoot(name);
        String current = plugin.getConfig().contains(path) ? revision(path) : null;
        if (expectedRevision != null && !expectedRevision.isBlank() && !expectedRevision.equals(current))
            return SaveResult.conflict(current);
        List<String> errors = validate(data);
        if (!errors.isEmpty()) return SaveResult.invalid(errors);
        plugin.getConfig().set(path, null);
        writeMap(path, data);
        plugin.getConfig().set(path + ".public-owner", owner.toString());
        plugin.getConfig().set(path + ".public-by", publisher == null || publisher.isBlank() ? owner.toString() : publisher);
        plugin.saveConfig();
        return SaveResult.saved(revision(path));
    }

    List<Map<String, Object>> listItems(UUID owner) {
        String root = "custom-items." + owner;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(root);
        if (section == null) return List.of();
        return section.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).map(name -> {
            String path = root + "." + name;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("reference", "@" + owner + "/" + name);
            item.put("title", plugin.getConfig().getString(path + ".title", name));
            item.put("material", plugin.getConfig().getString(path + ".material", ""));
            item.put("captured", plugin.getConfig().contains(path + ".item-bytes"));
            return item;
        }).toList();
    }

    DialogueDocument get(UUID owner, String rawName) {
        String name = sanitize(rawName);
        String path = ownerRoot(owner) + "." + name;
        if (!plugin.getConfig().contains(path)) return null;
        return new DialogueDocument(name, revision(path), readSection(path));
    }

    SaveResult save(UUID owner, String rawName, String expectedRevision, Map<String, Object> data) {
        String name = sanitize(rawName);
        String path = ownerRoot(owner) + "." + name;
        String current = plugin.getConfig().contains(path) ? revision(path) : null;
        if (expectedRevision != null && !expectedRevision.isBlank() && !expectedRevision.equals(current))
            return SaveResult.conflict(current);

        List<String> errors = validate(data);
        if (!errors.isEmpty()) return SaveResult.invalid(errors);

        plugin.getConfig().set(path, null);
        writeMap(path, data);
        plugin.saveConfig();
        return SaveResult.saved(revision(path));
    }

    DeleteResult delete(UUID owner, String rawName, String expectedRevision) {
        String name = sanitize(rawName);
        String path = ownerRoot(owner) + "." + name;
        if (!plugin.getConfig().contains(path)) return new DeleteResult(false, false, null);
        String current = revision(path);
        if (expectedRevision != null && !expectedRevision.isBlank() && !expectedRevision.equals(current))
            return new DeleteResult(false, true, current);
        if (plugin.getConfig().getBoolean(path + ".built-in-example", false)) {
            String dismissedPath = "dismissed-examples." + owner;
            ArrayList<String> dismissed = new ArrayList<>(plugin.getConfig().getStringList(dismissedPath));
            if (!dismissed.contains(name)) dismissed.add(name);
            plugin.getConfig().set(dismissedPath, dismissed);
        }
        plugin.getConfig().set(path, null);
        plugin.saveConfig();
        return new DeleteResult(true, false, null);
    }

    List<String> validate(Map<String, Object> data) {
        ArrayList<String> issues = new ArrayList<>();
        if (codePointLength(data.get("title")) > MAXIMUM_TITLE_CHARACTERS)
            issues.add("대화 제목은 최대 60자까지 사용할 수 있습니다.");
        Object defaultSpeaker = data.get("speaker");
        if (visibleLength(defaultSpeaker == null ? "" : String.valueOf(defaultSpeaker)) > MAXIMUM_SPEAKER_CHARACTERS)
            issues.add("기본 화자 이름은 최대 10자까지 사용할 수 있습니다.");

        Object pagesValue = data.get("message-pages");
        List<?> pages = pagesValue instanceof List<?> list ? list : List.of(data.getOrDefault("message", ""));
        if (pages.isEmpty()) issues.add("대화 페이지가 없습니다.");
        if (pages.size() > MAXIMUM_PAGES) issues.add("대화는 최대 30페이지까지 사용할 수 있습니다.");

        Object speakersValue = data.get("page-speakers");
        if (speakersValue instanceof Map<?, ?> speakers) speakers.forEach((page, speaker) -> {
            if (visibleLength(String.valueOf(speaker == null ? "" : speaker)) > MAXIMUM_SPEAKER_CHARACTERS)
                issues.add("Page " + (integer(page) + 1) + ": 화자 이름은 최대 10자까지 사용할 수 있습니다.");
        });

        for (int page = 0; page < pages.size(); page++) {
            validateTextPage(pages.get(page), "Page " + (page + 1), issues);
            Object pageChoices = nested(data, "page-choices", Integer.toString(page));
            if (pageChoices instanceof Map<?, ?> choices)
                validateChoices(choices, pages.size(), "Page " + (page + 1), 0, issues);
        }
        return issues;
    }

    private void validateTextPage(Object value, String location, List<String> issues) {
        String[] lines = String.valueOf(value == null ? "" : value).split("\\n", -1);
        if (lines.length > MAXIMUM_LINES) issues.add(location + ": 대사가 4줄을 초과했습니다.");
        for (int line = 0; line < lines.length; line++) {
            if (visibleLength(lines[line]) > MAXIMUM_CHARACTERS_PER_LINE)
                issues.add(location + " / " + (line + 1) + "줄: 표시 문자가 30자를 초과했습니다.");
        }
    }

    private void validateChoices(Map<?, ?> choices, int pageCount, String location, int depth, List<String> issues) {
        int count = integer(choices.get("choice-count"));
        if (count == 0) return;
        if (depth >= MAXIMUM_CHOICE_DEPTH) {
            issues.add(location + ": 중첩 선택지는 최대 16단계까지 사용할 수 있습니다.");
            return;
        }
        if (count < 0 || count > MAXIMUM_CHOICES)
            issues.add(location + ": 선택지는 0~8개까지 사용할 수 있습니다.");
        for (int choice = 1; choice <= Math.min(Math.max(count, 0), MAXIMUM_CHOICES); choice++) {
            String choiceLocation = location + " / 선택지 " + choice;
            Object labelValue = choices.get("choice-" + choice);
            String label = labelValue == null ? "" : String.valueOf(labelValue);
            if (label.isBlank()) issues.add(choiceLocation + ": 선택지 이름이 비어 있습니다.");
            else if (visibleLength(label) > MAXIMUM_CHOICE_CHARACTERS)
                issues.add(choiceLocation + ": 선택지 이름은 최대 10자까지 사용할 수 있습니다.");
            Object speakerValue = choices.get("speaker-" + choice);
            if (visibleLength(speakerValue == null ? "" : String.valueOf(speakerValue)) > MAXIMUM_SPEAKER_CHARACTERS)
                issues.add(choiceLocation + ": 화자 이름은 최대 10자까지 사용할 수 있습니다.");

            int target = integer(choices.get("target-page-" + choice));
            if (target < 0 || target > pageCount)
                issues.add(choiceLocation + ": 대상 페이지가 존재하지 않습니다.");

            Object responseValue = choices.get("response-pages-" + choice);
            List<?> responses = responseValue instanceof List<?> list ? list : List.of();
            if (responses.size() > MAXIMUM_PAGES)
                issues.add(choiceLocation + ": 후속 대사는 최대 30페이지까지 사용할 수 있습니다.");
            for (int response = 0; response < responses.size(); response++)
                validateTextPage(responses.get(response), choiceLocation + " / 후속 Page " + (response + 1), issues);

            Object nestedValue = choices.get("response-page-choices-" + choice);
            if (nestedValue instanceof Map<?, ?> nestedPages) {
                for (Map.Entry<?, ?> entry : nestedPages.entrySet()) {
                    int response = integer(entry.getKey());
                    if (response < 0 || response >= responses.size()) {
                        issues.add(choiceLocation + ": 존재하지 않는 후속 Page의 선택지가 설정되어 있습니다.");
                        continue;
                    }
                    if (entry.getValue() instanceof Map<?, ?> nested)
                        validateChoices(nested, pageCount, choiceLocation + " / 후속 Page " + (response + 1), depth + 1, issues);
                }
            }
        }
    }

    private int codePointLength(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.codePointCount(0, text.length());
    }

    private int pageCount(String path) {
        List<String> pages = plugin.getConfig().getStringList(path + ".message-pages");
        return pages.isEmpty() ? 1 : pages.size();
    }

    private Map<String, Object> readSection(String path) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        return section == null ? Map.of() : WebJson.sectionToMap(section);
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> data, String section, String key) {
        Object root = data.get(section);
        if (!(root instanceof Map<?, ?> map)) return null;
        return ((Map<String, Object>) map).get(key);
    }

    private void writeMap(String root, Map<String, Object> data) {
        data.forEach((key, value) -> {
            String path = root + "." + key;
            if (value instanceof Map<?, ?> map) {
                plugin.getConfig().createSection(path);
                Map<String, Object> child = new LinkedHashMap<>();
                map.forEach((childKey, childValue) -> child.put(String.valueOf(childKey), childValue));
                writeMap(path, child);
            } else {
                plugin.getConfig().set(path, value);
            }
        });
    }

    private String revision(String path) {
        Map<String, Object> data = readSection(path);
        String canonical = WebJson.stringify(sort(data));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
            map.forEach((key, child) -> sorted.put(String.valueOf(key), sort(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sort).toList();
        return value;
    }

    private int visibleLength(String text) {
        return TextWidthRules.visibleCharacters(text);
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String ownerRoot(UUID owner) {
        return "player-dialogues." + owner;
    }

    private String publicRoot(String name) {
        return "public-dialogues." + name;
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "default";
        String clean = raw.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return clean.isBlank() ? "default" : clean;
    }

    record DialogueDocument(String name, String revision, Map<String, Object> data) {}

    record SaveResult(boolean saved, boolean conflict, String revision, List<String> errors) {
        static SaveResult saved(String revision) {
            return new SaveResult(true, false, revision, List.of());
        }

        static SaveResult conflict(String revision) {
            return new SaveResult(false, true, revision, List.of());
        }

        static SaveResult invalid(List<String> errors) {
            return new SaveResult(false, false, null, List.copyOf(errors));
        }
    }

    record DeleteResult(boolean deleted, boolean conflict, String revision) {}
}
