package kr.hyuni.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
        assertTrue(yaml.getStringList(tutorial + ".message-pages").stream().anyMatch(page -> page.contains("직접 채집") && page.contains("사냥")));
        assertFalse(yaml.isConfigurationSection(tutorial + ".page-choices"));
    }
}
