package kr.hyuni.marketplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {
    @Test void rendersConfiguredMarketAndChecksFishingWindow() {
        String text = MarketText.render(Map.of("apple", 99L, "oak_log", 20L, "wheat", 12L, "wool", 18L, "iron_ore", 30L, "cod", 16L, "salmon", 22L));
        assertTrue(text.contains("사과 99원"));
        assertTrue(text.contains("대구 16원 · 연어 22원"));
        assertTrue(MarketText.bulletin(List.of()).contains("/marketplay tools"));
        assertEquals("사용자 공지", MarketText.bulletin(List.of("사용자 공지")));
        assertTrue(FishingTiming.caught(1500L, 1500L));
        assertFalse(FishingTiming.caught(1500L, 1501L));
        assertFalse(FishingTiming.caught(null, 1000L));
    }

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
            String requestId = UUID.randomUUID().toString();
            profile.setMoney(store.changeMoney(profile, 30, false, "test", requestId).join());
            assertEquals(1030, store.changeMoney(profile, 30, false, "test", requestId).join());
            assertThrows(Exception.class, () -> store.changeMoney(profile, Long.MAX_VALUE, false, "overflow", UUID.randomUUID().toString()).join());
        }
        try (ProfileStore reopened = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            PlayerProfile profile = reopened.load(id).join();
            assertEquals(1030, profile.money());
            assertEquals(5, profile.innerPower());
            assertEquals(10, profile.experience(Skill.MINING));
        }
    }

    @Test void purchaseGrantAndCrashSafeSaleAreIdempotent(@TempDir Path directory) throws Exception {
        UUID id = UUID.randomUUID();
        try (ProfileStore store = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            PlayerProfile profile = store.load(id).join();
            String grantId = UUID.randomUUID().toString();
            profile.setMoney(store.purchase(profile, 100, "tool:old_net", new byte[]{1, 2, 3}, grantId).join());
            assertEquals(900, profile.money());
            assertEquals(1, store.pendingGrants(id).join().size());
            store.acknowledgeGrant(grantId).join();
            assertTrue(store.pendingGrants(id).join().isEmpty());

            String intentId = UUID.randomUUID().toString();
            store.beginSale(profile, intentId, new byte[]{4, 5, 6}, "apple", 2, 15).join();
            assertEquals("PREPARED", store.pendingSale(id).join().orElseThrow().state());
            store.markSaleRemoving(intentId).join();
            profile.setMoney(store.completeSale(profile, intentId).join());
            assertEquals(930, profile.money());
            assertEquals(930, store.completeSale(profile, intentId).join());
            assertTrue(store.pendingSale(id).join().isEmpty());
        }
        try (ProfileStore reopened = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            assertEquals(930, reopened.load(id).join().money());
        }
    }
}
