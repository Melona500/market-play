package kr.hyuni.marketplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    @Test void rankAndSkillProgression() {
        var ranks = new LinkedHashMap<String, Long>();
        ranks.put("평민", 0L);
        ranks.put("남작", 500L);
        RankTable table = new RankTable(ranks);
        assertEquals("평민", table.rankFor(499));
        assertEquals("남작", table.rankFor(500));

        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), 1000, 0, 100);
        profile.addExperience(Skill.FISHING, 100);
        assertEquals(2, profile.level(Skill.FISHING));
        assertTrue(profile.spendVitality(20));
        assertEquals(80, profile.vitality());
        assertFalse(profile.spendVitality(81));
        ranks.put("잘못된 계급", 400L);
        assertThrows(IllegalArgumentException.class, () -> new RankTable(ranks));
    }

    @Test void sqlitePersistsAndLogsSale(@TempDir Path directory) throws Exception {
        UUID id = UUID.randomUUID();
        try (ProfileStore store = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            PlayerProfile profile = store.load(id).join();
            profile.addInnerPower(5);
            profile.addExperience(Skill.MINING, 10);
            store.save(profile).join();
            profile.setMoney(store.sell(profile, "COAL", 2, 10).join());
            profile.setMoney(store.changeMoney(profile, 30, false, "test").join());
            assertThrows(Exception.class, () -> store.changeMoney(profile, Long.MAX_VALUE, false, "overflow").join());
        }
        try (ProfileStore reopened = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            PlayerProfile profile = reopened.load(id).join();
            assertEquals(1050, profile.money());
            assertEquals(5, profile.innerPower());
            assertEquals(10, profile.experience(Skill.MINING));
        }
    }
}
