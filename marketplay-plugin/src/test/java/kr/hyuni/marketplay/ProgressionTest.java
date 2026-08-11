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
        assertTrue(table.atLeast(500, "남작"));
        assertFalse(table.atLeast(499, "남작"));

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
            profile.setDeepOmen(72);
            profile.addRoyalReputation(30);
            profile.setKnightState("DUEL");
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
            assertEquals(72, profile.deepOmen());
            assertEquals(30, profile.royalReputation());
            assertEquals("DUEL", profile.knightState());
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

    @Test void explorationNodesAndIntentsAreRestartSafe(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("marketplay.db");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        try (ProfileStore store = new ProfileStore(database, 1000, 100)) {
            PlayerProfile a = store.load(first).join(), b = store.load(second).join();
            ProfileStore.NodeHarvest harvest = store.harvestNode(a, "fairy:crystal", 1000, 2000, 1, Skill.MINING, 2, new byte[]{1}, "node-grant").join();
            assertEquals(99, harvest.vitality());
            assertThrows(Exception.class, () -> store.harvestNode(b, "fairy:crystal", 1001, 2001, 1, Skill.MINING, 2, new byte[]{2}, "duplicate-node").join());

            ProfileStore.ExplorationIntent craft = new ProfileStore.ExplorationIntent("craft", first, "CRAFT", new byte[]{3}, new byte[]{4}, new byte[]{5}, null, "craft-grant");
            store.prepareExplorationIntent(craft).join();
            assertEquals("PREPARED", store.activeExplorationIntent(first).join().orElseThrow().state());
            store.markExplorationRemoving("craft").join();
            assertEquals("CRAFT", store.completeExplorationIntent(a, "craft").join().kind());
            assertEquals("CRAFT", store.completeExplorationIntent(a, "craft").join().kind());

            ProfileStore.ExplorationIntent royal = new ProfileStore.ExplorationIntent("royal", first, "ROYAL", new byte[]{6}, null, null, "gift-token", null);
            store.prepareExplorationIntent(royal).join(); store.markExplorationRemoving("royal").join();
            ProfileStore.IntentResult result = store.completeExplorationIntent(a, "royal").join();
            assertEquals(10, result.value());
            a.setRoyalReputation(result.value());
            ProfileStore.Encounter encounter = store.startEncounter(a).join();
            store.saveEncounterHp(encounter.id(), 75).join();
        }
        try (ProfileStore reopened = new ProfileStore(database, 1000, 100)) {
            PlayerProfile a = reopened.load(first).join();
            assertEquals(1, a.innerPower());
            assertEquals(2, a.experience(Skill.MINING));
            assertEquals(5, a.experience(Skill.JEWELCRAFTING));
            assertEquals(10, a.royalReputation());
            assertEquals(75, reopened.activeEncounter().join().orElseThrow().hp());
            String encounter = reopened.activeEncounter().join().orElseThrow().id();
            assertEquals(30, reopened.defeatEncounter(encounter, List.of(new ProfileStore.BossReward(first, "boss-grant", new byte[]{9}))).join().get(first));
            assertTrue(reopened.activeEncounter().join().isEmpty());
            assertEquals(3, reopened.pendingGrants(first).join().size());
            assertThrows(Exception.class, () -> reopened.harvestNode(reopened.load(second).join(), "fairy:crystal", 1500, 2500, 1, Skill.MINING, 2, new byte[]{7}, "restart-duplicate").join());
            assertDoesNotThrow(() -> reopened.harvestNode(reopened.load(second).join(), "fairy:crystal", 2000, 3000, 1, Skill.MINING, 2, new byte[]{8}, "after-cooldown").join());
        }
    }

    @Test void queuedSaveAndUnloadKeepCommittedExplorationProgress(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("marketplay.db");
        UUID id = UUID.randomUUID();
        try (ProfileStore store = new ProfileStore(database, 1000, 100)) {
            PlayerProfile profile = store.load(id).join();
            var harvest = store.harvestNode(profile, "joy:ruby", 1000, 2000, 1, Skill.MINING, 2, new byte[]{1}, "queued-node");
            store.save(profile).join();
            harvest.join();
            var purchase = store.purchaseItem(profile, 100, "oxygen_device", new byte[]{2}, "queued-purchase");
            String encounter = store.startEncounter(profile).join().id();
            var defeat = store.defeatEncounter(encounter, List.of(new ProfileStore.BossReward(id, "queued-boss", new byte[]{3})));
            store.unload(id).join();
            purchase.join();
            defeat.join();
        }
        try (ProfileStore reopened = new ProfileStore(database, 1000, 100)) {
            PlayerProfile profile = reopened.load(id).join();
            assertEquals(900, profile.money());
            assertEquals(1, profile.innerPower());
            assertEquals(2, profile.experience(Skill.MINING));
            assertEquals(20, profile.royalReputation());
            assertEquals(3, reopened.pendingGrants(id).join().size());
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

    @Test void areaRequiresMainWorldAndProtectedHeight() {
        Area river = new Area(9, 9, 24, 24);
        assertTrue(river.contains("world", 9, 62, 9));
        assertFalse(river.contains("world_nether", 9, 62, 9));
        assertFalse(river.contains("world", 9, 61, 9));
    }

    @Test void socialEconomyEscrowsPersistAndSettleAtomically(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("marketplay.db");
        UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
        try (ProfileStore store = new ProfileStore(database, 1000, 100)) {
            PlayerProfile sellerProfile = store.load(seller).join(), buyerProfile = store.load(buyer).join();
            ProfileStore.SocialIntent listing = new ProfileStore.SocialIntent("intent-list", seller, "EXCHANGE", "listing-one", new byte[]{1, 2}, "apple", 2, 150, 4, "Seller");
            store.prepareSocialIntent(listing).join();
            assertEquals("PREPARED", store.pendingSocialIntent(seller).join().orElseThrow().state());
            store.markSocialRemoving(listing.id()).join();
            assertEquals("EXCHANGE", store.completeSocialIntent(listing.id()).join().kind());
            assertEquals("EXCHANGE", store.completeSocialIntent(listing.id()).join().kind());
            assertTrue(store.pendingSocialIntent(seller).join().isEmpty());
            assertEquals(4, store.exchangeListings(null, 10).join().getFirst().quality());

            ProfileStore.ExchangePurchase purchase = store.buyListing(buyerProfile, "listing", "exchange-grant").join();
            assertEquals(700, purchase.buyerBalance());
            assertEquals(1300, purchase.sellerBalance());
            assertEquals(1, store.pendingGrants(buyer).join().size());
            assertEquals(150, store.tradeStats("apple").join().averagePrice());
            assertThrows(Exception.class, () -> store.buyListing(buyerProfile, "listing", "duplicate-grant").join());
            ProfileStore.SocialIntent cancelled = new ProfileStore.SocialIntent("intent-cancel", seller, "EXCHANGE", "listing-cancel", new byte[]{8}, "wheat", 3, 20, 2, "Seller");
            store.prepareSocialIntent(cancelled).join(); store.markSocialRemoving(cancelled.id()).join(); store.completeSocialIntent(cancelled.id()).join();
            assertArrayEquals(new byte[]{8}, store.cancelListing(seller, "listing-c", "cancel-grant").join().item());
            store.claimStall(1, seller, "Seller").join();
            assertEquals(seller, store.stalls().join().getFirst().owner());

            ProfileStore.ServiceOffer offer = store.createService(seller, "Seller", "CHEF", 100).join();
            store.hireService(buyerProfile, offer.shortId()).join();
            assertEquals(600, buyerProfile.money());
            store.submitService(seller, offer.shortId()).join();
            store.approveService(buyerProfile, offer.shortId()).join();
            assertEquals(1400, sellerProfile.money());

            ProfileStore.Guild guild = store.createGuild(seller, "Seller", "시장상단").join();
            ProfileStore.SocialIntent deposit = new ProfileStore.SocialIntent("warehouse", seller, "GUILD", guild.id(), new byte[]{9}, "ruby", 5, 0, 5, "Seller");
            store.prepareSocialIntent(deposit).join(); store.markSocialRemoving(deposit.id()).join(); store.completeSocialIntent(deposit.id()).join();
            assertEquals(5, store.guildItems(seller).join().getFirst().quantity());
            assertArrayEquals(new byte[]{9}, store.withdrawGuildItem(seller, "ware", "warehouse-grant").join().item());
            store.changeMoney(sellerProfile, 3000, false, "test", "social-funds").join();
            store.contributeGuildMoney(sellerProfile, 2000).join();
            for (var intent : List.of(
                    new ProfileStore.SocialIntent("logs", seller, "PROJECT", guild.id(), new byte[]{3}, "LOG", 64, 0, 1, "Seller"),
                    new ProfileStore.SocialIntent("iron", seller, "PROJECT", guild.id(), new byte[]{4}, "IRON", 32, 0, 1, "Seller"))) {
                store.prepareSocialIntent(intent).join(); store.markSocialRemoving(intent.id()).join(); store.completeSocialIntent(intent.id()).join();
            }
            assertEquals("COMPLETE", store.guildFor(seller).join().orElseThrow().projectState());

            store.openRestaurant(seller, "시장식당").join();
            UUID rival = UUID.randomUUID();
            store.openRestaurant(rival, "경쟁식당").join();
            store.assignRestaurantRole(seller, buyer, "Buyer", "CHEF").join();
            assertThrows(Exception.class, () -> store.assignRestaurantRole(rival, buyer, "Buyer", "SERVER").join());
            ProfileStore.RestaurantOrder order = store.createRestaurantOrder(seller).join();
            int index = 0;
            for (String category : List.of("CROP", "PROTEIN", "EXTRA")) {
                String id = "ingredient-" + index++;
                ProfileStore.SocialIntent ingredient = new ProfileStore.SocialIntent(id, seller, "RESTAURANT", order.id(), new byte[]{5}, category, 1, 0, 3, "Seller");
                store.prepareSocialIntent(ingredient).join(); store.markSocialRemoving(id).join(); store.completeSocialIntent(id).join();
            }
            store.restaurantAction(seller, "COOK", 1000).join();
            store.restaurantAction(seller, "FLIP", 5000).join();
            store.restaurantAction(seller, "PLATE", 7500).join();
            ProfileStore.RestaurantResult served = store.serveRestaurant(seller).join();
            assertEquals(4, served.rating());
            assertEquals(260, served.reward());
            assertTrue(store.restaurantOrderFor(seller).join().isEmpty());
        }
        try (ProfileStore reopened = new ProfileStore(database, 1000, 100)) {
            assertEquals(2660, reopened.load(seller).join().money());
            assertEquals(600, reopened.load(buyer).join().money());
            assertEquals("COMPLETE", reopened.guildFor(seller).join().orElseThrow().projectState());
            assertTrue(reopened.exchangeListings(null, 10).join().isEmpty());
            assertEquals(2, reopened.pendingGrants(seller).join().size());
        }
    }

    @Test void housingPersistsPermissionsUpgradeGuestbookAndGift(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("marketplay.db");
        UUID owner = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        try (ProfileStore profiles = new ProfileStore(database, 1000, 100);
             HousingStore housing = new HousingStore(database)) {
            PlayerProfile profile = profiles.load(owner).join();
            profiles.load(visitor).join();
            housing.remember(owner, "Owner").join();
            housing.remember(visitor, "Visitor").join();
            HousingStore.House house = housing.ensureHouse(owner, "Owner").join();
            assertEquals("CREATING", house.state());
            housing.markReady(owner).join();
            assertEquals("public", housing.visibility(owner, "public").join().visibility());
            assertEquals(HousePermission.VISIT.bit, housing.permission(owner, visitor, HousePermission.VISIT, true).join());
            assertEquals(HousePermission.VISIT.bit, housing.permissions(owner).join().get(visitor));

            String upgradeId = UUID.randomUUID().toString();
            HousingStore.UpgradeIntent intent = housing.prepareUpgrade(owner, 2, upgradeId, List.of(new HousingStore.RefundItem(UUID.randomUUID().toString(), new byte[]{9}))).join();
            housing.markUpgradeRemoving(intent.id()).join();
            HousingStore.UpgradeResult upgrade = housing.upgrade(profile, 1, 100, 0, 0, 0, intent.id()).join();
            housing.finishUpgrade(intent.id()).join();
            assertEquals(2, upgrade.level());
            assertEquals(900, upgrade.balance());
            profile.setMoney(upgrade.balance());
            assertEquals(upgrade, housing.upgrade(profile, 1, 100, 0, 0, 0, intent.id()).join());
            assertTrue(housing.activeUpgrade(owner).join().isEmpty());

            HousingStore.GuestbookEntry entry = housing.writeGuestbook(owner, visitor, "Visitor", "좋은 집입니다").join();
            assertTrue(housing.reportGuestbook(owner, entry.shortId()).join());
            assertTrue(housing.deleteGuestbook(owner, entry.shortId()).join());
            housing.blockGuest(owner, visitor).join();
            assertThrows(Exception.class, () -> housing.writeGuestbook(owner, visitor, "Visitor", "차단 우회").join());

            String grantId = UUID.randomUUID().toString();
            byte[] item = new byte[]{1, 2, 3};
            HousingStore.GiftIntent gift = housing.prepareGift(owner, "Owner", visitor, "선물", grantId, item).join();
            housing.markGiftRemoving(gift.id()).join();
            HousingStore.Mail mail = housing.completeGift(gift.id()).join();
            assertEquals("GIFT", mail.kind());
            assertEquals(1, profiles.pendingGrants(visitor).join().size());
            assertEquals(mail, housing.completeGift(gift.id()).join());
        }
        try (HousingStore reopened = new HousingStore(database)) {
            assertEquals(2, reopened.houseByOwner(owner).join().orElseThrow().level());
            assertEquals(1, reopened.mail(visitor).join().size());
        }
    }

    @Test void artworkPublishingOwnershipAndTradeAreAtomic(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("marketplay.db");
        UUID artist = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        try (ProfileStore profiles = new ProfileStore(database, 1000, 100);
             HousingStore housing = new HousingStore(database);
             ArtStore art = new ArtStore(database)) {
            profiles.load(artist).join();
            profiles.load(buyer).join();
            ArtStore.Artwork draft = art.create(artist, "Artist", "첫 작품", 77).join();
            byte[] pixels = new byte[45];
            pixels[0] = 2;
            art.saveDraft(draft.id(), artist, pixels).join();
            ArtStore.Artwork published = art.publish(draft.id(), artist).join();
            assertEquals("PUBLISHED", published.state());
            assertThrows(Exception.class, () -> art.saveDraft(draft.id(), artist, new byte[45]).join());
            assertEquals(0, art.exhibit(draft.id(), artist).join());

            art.listForSale(draft.id(), artist, 250).join();
            ArtStore.Artwork rotated = art.rotateToken(draft.id(), artist, "seller-token").join();
            assertNotEquals(published.itemToken(), rotated.itemToken());
            ArtStore.BuyResult bought = art.buy(draft.id(), buyer, "buyer-token", "buy-grant", new byte[]{1, 2, 3}).join();
            assertEquals(buyer, bought.artwork().owner());
            assertEquals(750, bought.buyerBalance());
            assertEquals(1250, bought.sellerBalance());
            assertEquals(1, profiles.pendingGrants(buyer).join().size());
            assertThrows(Exception.class, () -> art.buy(draft.id(), buyer, "duplicate-token", "duplicate", new byte[]{1}).join());

            ArtStore.Artwork gifted = art.gift(draft.id(), buyer, "Buyer", artist, "gift-token", "gift-grant", new byte[]{4, 5, 6}).join();
            assertEquals(artist, gifted.owner());
            assertEquals(1, housing.mail(artist).join().size());
            assertEquals(1, profiles.pendingGrants(artist).join().size());
        }
        try (ArtStore reopened = new ArtStore(database)) {
            ArtStore.Artwork artwork = reopened.all().join().getFirst();
            assertEquals(artist, artwork.owner());
            assertEquals(2, artwork.pixels()[0]);
            assertEquals(1, reopened.exhibits().join().size());
        }
    }
}
