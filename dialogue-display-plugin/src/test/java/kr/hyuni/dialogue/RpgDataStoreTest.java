package kr.hyuni.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpgDataStoreTest {
    @Test
    void separatesAndReloadsPlayerData(@TempDir Path folder) throws Exception {
        UUID owner = UUID.fromString("12d0a2f0-6e70-4f53-88f7-9571b4c6bced");
        YamlConfiguration live = new YamlConfiguration();
        live.set("distance", 1.8);
        live.set("player-dialogues." + owner + ".인사.message", "안녕");
        live.set("custom-items." + owner + ".열쇠.material", "minecraft:tripwire_hook");
        live.set("dismissed-examples." + owner, java.util.List.of("예제"));
        live.set("player-variables." + owner + "." + DialogueDisplayPlugin.variableDataKey("호감도"), "10");
        live.set("shared-dialogues.token.message", "공유");
        live.set("public-dialogues.공용.message", "모두 보기");

        RpgDataStore store = new RpgDataStore(folder, Logger.getAnonymousLogger());
        store.save(live);

        String settings = Files.readString(folder.resolve("config.yml"));
        assertTrue(settings.contains("distance: 1.8"));
        assertFalse(settings.contains("player-dialogues"));
        assertTrue(Files.readString(folder.resolve("players").resolve(owner + ".yml")).contains("variables:"));
        String common = Files.readString(folder.resolve("common.yml"));
        assertTrue(common.contains("public-dialogues"));
        assertFalse(common.contains("shared-dialogues"));
        assertTrue(Files.readString(folder.resolve("shares.yml")).contains("shared-dialogues"));

        YamlConfiguration restored = new YamlConfiguration();
        store.loadInto(restored);
        assertEquals("안녕", restored.getString("player-dialogues." + owner + ".인사.message"));
        assertEquals("10", restored.getString("player-variables." + owner + "." + DialogueDisplayPlugin.variableDataKey("호감도")));
        assertEquals("공유", restored.getString("shared-dialogues.token.message"));
        assertEquals("모두 보기", restored.getString("public-dialogues.공용.message"));
    }

    @Test
    void migratesLegacySharedDataOutOfCommon(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("common.yml"), """
                shared-dialogues:
                  legacytoken:
                    message: legacy share
                public-dialogues:
                  public_one:
                    message: public dialogue
                """);

        RpgDataStore store = new RpgDataStore(folder, Logger.getAnonymousLogger());
        YamlConfiguration live = new YamlConfiguration();
        store.loadInto(live);
        assertEquals("legacy share", live.getString("shared-dialogues.legacytoken.message"));
        assertEquals("public dialogue", live.getString("public-dialogues.public_one.message"));

        store.save(live);
        assertTrue(Files.readString(folder.resolve("shares.yml")).contains("legacytoken"));
        assertFalse(Files.readString(folder.resolve("common.yml")).contains("shared-dialogues"));

        YamlConfiguration restored = new YamlConfiguration();
        store.loadInto(restored);
        assertEquals("legacy share", restored.getString("shared-dialogues.legacytoken.message"));
        assertEquals("public dialogue", restored.getString("public-dialogues.public_one.message"));
    }

    @Test
    void deletedPlayerDataDoesNotReturnAfterRestart(@TempDir Path folder) throws Exception {
        UUID owner = UUID.fromString("12d0a2f0-6e70-4f53-88f7-9571b4c6bced");
        YamlConfiguration live = new YamlConfiguration();
        live.set("player-dialogues." + owner + ".삭제할_대화.message", "삭제됨");

        RpgDataStore store = new RpgDataStore(folder, Logger.getAnonymousLogger());
        store.save(live);
        Path playerFile = folder.resolve("players").resolve(owner + ".yml");
        assertTrue(Files.exists(playerFile));

        live.set("player-dialogues." + owner, null);
        store.save(live);
        assertFalse(Files.exists(playerFile));
        try (var backups = Files.list(folder.resolve("backups"))) {
            assertTrue(backups.anyMatch(path -> path.getFileName().toString().startsWith(owner + ".yml-")));
        }

        YamlConfiguration restored = new YamlConfiguration();
        store.loadInto(restored);
        assertFalse(restored.contains("player-dialogues." + owner));
    }

    @Test
    void snapshotsWholeGenerationBeforeReplacement(@TempDir Path folder) throws Exception {
        YamlConfiguration live = new YamlConfiguration();
        live.set("distance", 1.0);
        live.set("public-dialogues.example.message", "first");
        live.set("shared-dialogues.token.message", "share-first");

        RpgDataStore store = new RpgDataStore(folder, Logger.getAnonymousLogger());
        store.save(live);

        live.set("distance", 2.0);
        live.set("public-dialogues.example.message", "second");
        live.set("shared-dialogues.token.message", "share-second");
        store.save(live);

        try (var backups = Files.list(folder.resolve("backups"))) {
            Path generation = backups
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("generation-"))
                    .filter(path -> Files.isRegularFile(path.resolve("config.yml")))
                    .findFirst()
                    .orElseThrow();
            assertTrue(Files.readString(generation.resolve("config.yml")).contains("distance: 1.0"));
            assertTrue(Files.readString(generation.resolve("common.yml")).contains("first"));
            assertTrue(Files.readString(generation.resolve("shares.yml")).contains("share-first"));
        }
    }
}
