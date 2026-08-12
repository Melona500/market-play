package kr.hyuni.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPlayDialogueConfigTest {
    @Test
    void npcDialoguesOpenTheirMenus() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("..", "plugins", "RPGMaker", "common.yml").toFile());
        Map<String, String> commands = Map.of(
                "시장놀이_시장안내", "marketplay dialogue-menu market",
                "시장놀이_게시판안내", "marketplay dialogue-menu board",
                "시장놀이_주택안내", "marketplay dialogue-menu housing",
                "시장놀이_여행안내", "marketplay dialogue-menu travel",
                "시장놀이_모험안내", "marketplay dialogue-menu adventure",
                "시장놀이_시설안내", "marketplay dialogue-menu hub");
        commands.forEach((dialogue, command) -> {
            String root = "public-dialogues." + dialogue;
            assertTrue(yaml.isConfigurationSection(root), dialogue);
            assertEquals(command, yaml.getString(root + ".page-choices.0.response-effects-1.0.command"));
            assertFalse(yaml.getString(root + ".page-choices.0.choice-1", "").contains("열기"));
            assertFalse(yaml.getString(root + ".page-choices.0.choice-2", "").matches(".*(나가기|다음에).*"));
        });
        String tutorial = "public-dialogues.시장놀이_첫걸음";
        assertTrue(yaml.getStringList(tutorial + ".message-pages").stream().anyMatch(page -> page.contains("직접")));
        assertTrue(yaml.getStringList(tutorial + ".message-pages").stream().anyMatch(page -> page.contains("사냥")));
        assertFalse(yaml.isConfigurationSection(tutorial + ".page-choices"));
        assertEquals("marketplay dialogue-tutorial", yaml.getString(
                "public-dialogues.시장놀이_튜토리얼안내.page-effects.2.command"));
    }

    @Test
    void marketPlayNpcDialogueLinesStayWithinThirtyCharacters() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("..", "plugins", "RPGMaker", "common.yml").toFile());
        var section = yaml.getConfigurationSection("public-dialogues");
        assertTrue(section != null);
        section.getKeys(false).stream().filter(name -> name.startsWith("시장놀이_")).forEach(name -> {
            String root = "public-dialogues." + name;
            assertLines(name, yaml.getStringList(root + ".message-pages"));
            var choices = yaml.getConfigurationSection(root + ".page-choices");
            if (choices == null) return;
            choices.getKeys(false).forEach(page -> {
                String choiceRoot = root + ".page-choices." + page;
                int count = yaml.getInt(choiceRoot + ".choice-count");
                for (int i = 1; i <= count; i++) {
                    assertLines(name, List.of(yaml.getString(choiceRoot + ".choice-" + i, "")));
                    assertLines(name, yaml.getStringList(choiceRoot + ".response-pages-" + i));
                }
            });
        });
    }

    private static void assertLines(String dialogue, List<String> pages) {
        pages.stream().flatMap(page -> page.lines()).forEach(line -> {
            String visible = line.replaceAll("\\{#[0-9A-Fa-f]{6}(?::[^}]*)?}", "")
                    .replaceAll("#[0-9A-Fa-f]{6}:(?:[a-z,]+:)?", "");
            assertTrue(visible.codePointCount(0, visible.length()) <= 30, dialogue + ": " + line);
        });
    }
}
