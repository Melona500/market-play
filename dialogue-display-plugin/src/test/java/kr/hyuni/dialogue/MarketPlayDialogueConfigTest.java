package kr.hyuni.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPlayDialogueConfigTest {
    @Test
    void npcDialoguesOpenTheirMenus() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("..", "plugins", "RPGMaker", "common.yml").toFile());
        Map<String, String> commands = Map.of(
                "시장놀이_시장안내", "marketplay market",
                "시장놀이_게시판안내", "marketplay menu board",
                "시장놀이_주택안내", "marketplay menu housing",
                "시장놀이_여행안내", "marketplay menu travel",
                "시장놀이_모험안내", "marketplay menu adventure",
                "시장놀이_시설안내", "marketplay menu hub");
        commands.forEach((dialogue, command) -> {
            String root = "public-dialogues." + dialogue;
            assertTrue(yaml.isConfigurationSection(root), dialogue);
            assertEquals(command, yaml.getString(root + ".page-choices.0.response-effects-1.0.command"));
        });
    }
}
