package kr.hyuni.marketplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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
            String netRequest = UUID.randomUUID().toString();
            profile.setMoney(store.purchaseTool(profile, 100, "old_net", null, netRequest).join());
            assertEquals(900, profile.money());
            assertTrue(store.pendingGrants(id).join().isEmpty());
            assertEquals(900, store.purchaseTool(profile, 100, "old_net", null, netRequest).join());
            assertThrows(Exception.class, () -> store.purchaseTool(profile, 100, "old_net", null, UUID.randomUUID().toString()).join());
            profile.addTool("old_net");

            String rodRequest = UUID.randomUUID().toString();
            profile.setMoney(store.purchaseTool(profile, 100, "old_rod", new byte[]{1, 2, 3}, rodRequest).join());
            assertEquals(1, store.pendingGrants(id).join().size());
            store.migrateTools(profile, Set.of("old_rod"), Set.of(rodRequest)).join();
            assertTrue(store.pendingGrants(id).join().isEmpty());

            String intentId = UUID.randomUUID().toString();
            store.beginSale(profile, intentId, new byte[]{4, 5, 6}, "apple", 2, 15).join();
            assertEquals("PREPARED", store.pendingSale(id).join().orElseThrow().state());
            store.markSaleRemoving(intentId).join();
            profile.setMoney(store.completeSale(profile, intentId).join());
            assertEquals(830, profile.money());
            assertEquals(830, store.completeSale(profile, intentId).join());
            assertTrue(store.pendingSale(id).join().isEmpty());
        }
        try (ProfileStore reopened = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            PlayerProfile profile = reopened.load(id).join();
            assertEquals(830, profile.money());
            assertTrue(profile.hasTool("old_net"));
            assertTrue(profile.hasTool("old_rod"));
        }
    }

    @Test void dailyMarketAndBulletinPersist(@TempDir Path directory) throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 11);
        Map<String, Long> bases = Map.of("apple", 100L, "cod", 100L);
        MarketDay normal = MarketDay.create(day, bases, Map.of());
        MarketDay supplied = MarketDay.create(day, bases, Map.of("apple", 600L));
        assertTrue(supplied.entries().get("apple").unitPrice() < normal.entries().get("apple").unitPrice());
        assertEquals(1, normal.entries().values().stream().filter(entry -> entry.royalTarget() > 0).count());
        assertTrue(MarketText.render(normal).contains("왕실 특별 주문"));

        UUID author = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        MarketDay stored;
        try (ProfileStore store = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            stored = store.marketDay(day, bases, ZoneOffset.UTC).join();
            ProfileStore.BulletinPost post = store.postBulletin(author, "Tester", "식당 구인", now, Duration.ofMinutes(5), Duration.ofDays(1)).join();
            assertEquals("식당 구인", store.bulletins(now, 3).join().getFirst().body());
            assertThrows(Exception.class, () -> store.postBulletin(author, "Tester", "도배", now.plusSeconds(1), Duration.ofMinutes(5), Duration.ofDays(1)).join());
            assertFalse(store.deleteBulletin(post.shortId(), UUID.randomUUID(), false).join());
        }
        try (ProfileStore reopened = new ProfileStore(directory.resolve("marketplay.db"), 1000, 100)) {
            assertEquals(stored, reopened.marketDay(day, Map.of("apple", 999L, "cod", 999L), ZoneOffset.UTC).join());
            ProfileStore.BulletinPost post = reopened.bulletins(now, 3).join().getFirst();
            assertTrue(reopened.deleteBulletin(post.shortId(), author, false).join());
            assertTrue(reopened.bulletins(now, 3).join().isEmpty());
        }
    }
}
