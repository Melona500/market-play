package kr.hyuni.marketplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class SocialEconomyManager implements Listener {
    private static final Set<Material> CROP = Set.of(Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.APPLE);
    private static final Set<Material> PROTEIN = Set.of(Material.COD, Material.SALMON, Material.COOKED_COD, Material.COOKED_SALMON, Material.BEEF, Material.COOKED_BEEF, Material.PORKCHOP, Material.COOKED_PORKCHOP, Material.CHICKEN, Material.COOKED_CHICKEN);
    private static final Set<Material> EXTRA = Set.of(Material.SUGAR, Material.EGG, Material.PUMPKIN, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.HONEY_BOTTLE);
    private static final List<Location> STALLS = List.of(new Location(null, -6.5, 65.5, 15.5), new Location(null, -2.5, 65.5, 15.5), new Location(null, 2.5, 65.5, 15.5), new Location(null, 6.5, 65.5, 15.5));

    private final MarketPlayPlugin plugin;
    private final ProfileStore store;
    private final NamespacedKey intentKey;
    private final NamespacedKey stallKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey qualityKey;
    private final NamespacedKey itemIdKey;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final AtomicInteger stallRefresh = new AtomicInteger();

    SocialEconomyManager(MarketPlayPlugin plugin, ProfileStore store) {
        this.plugin = plugin; this.store = store;
        intentKey = new NamespacedKey(plugin, "social_intent"); stallKey = new NamespacedKey(plugin, "stall_slot");
        displayKey = new NamespacedKey(plugin, "social_display"); qualityKey = new NamespacedKey(plugin, "quality"); itemIdKey = new NamespacedKey(plugin, "item_id");
    }

    void start() { plugin.getServer().getPluginManager().registerEvents(this, plugin); refreshStalls(); }

    void stop() {
        stallRefresh.incrementAndGet();
        World world = Bukkit.getWorld("world"); if (world == null) return;
        world.getNearbyEntities(new Location(world, 0, 67, 15), 15, 6, 6).stream().filter(this::isSocialDisplay).forEach(Entity::remove);
    }

    boolean command(Player player, String[] args) {
        if (args.length == 0) return false;
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "exchange" -> exchange(player, args);
            case "stall" -> stall(player, args);
            case "guild" -> guild(player, args);
            case "service" -> service(player, args);
            case "restaurant" -> restaurant(player, args);
            default -> false;
        };
    }

    void recover(Player player, Runnable next) {
        store.pendingSocialIntent(player.getUniqueId()).whenComplete((pending, error) -> main(() -> {
            if (error != null) { player.kick(Component.text("사회경제 거래 상태를 확인하지 못했습니다.")); return; }
            if (pending.isEmpty()) { clearOrphanMarks(player); next.run(); return; }
            ProfileStore.SocialIntent intent = pending.get(); ItemStack marked = findMarked(player, intent.id());
            if (intent.state().equals("PREPARED") || marked != null) {
                if (marked != null) { marked.editPersistentDataContainer(data -> data.remove(intentKey)); player.saveData(); }
                store.cancelSocialIntent(intent.id()).whenComplete((ignored, cancelError) -> main(() -> { if (cancelError != null) player.kick(Component.text("사회경제 거래 취소 복구에 실패했습니다.")); else next.run(); }));
            } else store.completeSocialIntent(intent.id()).whenComplete((ignored, completeError) -> main(() -> { if (completeError != null) player.kick(Component.text("사회경제 거래 완료 복구에 실패했습니다.")); else { player.sendMessage(Component.text("중단됐던 사회경제 거래를 복구했습니다.", NamedTextColor.GREEN)); refreshStalls(); next.run(); } }));
        }));
    }

    private boolean exchange(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) { listExchange(player, null); return true; }
        if (args[1].equalsIgnoreCase("mine")) { listExchange(player, player.getUniqueId()); return true; }
        if (args[1].equalsIgnoreCase("sell") && args.length == 3) {
            long price = positiveLong(args[2]); if (price < 1) return usage(player, "/mp exchange sell <개당 가격>");
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (!tradable(hand)) { message(player, "판매할 안전한 아이템을 주손에 드세요.", false); return true; }
            String listing = UUID.randomUUID().toString();
            transfer(player, "EXCHANGE", listing, itemId(hand), price, quality(hand), ignored -> { message(player, "거래소 등록: " + listing.substring(0, 8), true); refreshStalls(); });
            return true;
        }
        if (args[1].equalsIgnoreCase("buy") && args.length == 3) {
            PlayerProfile profile = store.get(player.getUniqueId()); if (profile == null || !busy.add(player.getUniqueId())) return true;
            String grant = UUID.randomUUID().toString();
            store.buyListing(profile, args[2], grant).whenComplete((purchase, error) -> main(() -> {
                busy.remove(player.getUniqueId()); if (error != null) { message(player, "매물을 구매할 수 없습니다.", false); return; }
                plugin.deliverPendingGrants(player); message(player, "구매 완료 · 잔액 " + purchase.buyerBalance() + "원", true); refreshStalls();
            }));
            return true;
        }
        if (args[1].equalsIgnoreCase("cancel") && args.length == 3) {
            if (!busy.add(player.getUniqueId())) return true; String grant = UUID.randomUUID().toString();
            store.cancelListing(player.getUniqueId(), args[2], grant).whenComplete((item, error) -> main(() -> {
                busy.remove(player.getUniqueId()); if (error != null) { message(player, "취소할 매물을 찾지 못했습니다.", false); return; }
                plugin.deliverPendingGrants(player); message(player, "매물 취소 완료", true); refreshStalls();
            }));
            return true;
        }
        if (args[1].equalsIgnoreCase("stats")) {
            ItemStack hand = player.getInventory().getItemInMainHand(); if (hand.getType().isAir()) return usage(player, "아이템을 주손에 들고 /mp exchange stats");
            store.tradeStats(itemId(hand)).whenComplete((stats, error) -> main(() -> message(player, error == null ? "최근 거래가 " + stats.recentPrice() + "원 · 7일 평균 " + stats.averagePrice() + "원" : "거래 통계를 읽지 못했습니다.", error == null)));
            return true;
        }
        return usage(player, "/mp exchange list|mine|sell <가격>|buy <번호>|cancel <번호>|stats");
    }

    private void listExchange(Player player, UUID seller) {
        store.exchangeListings(seller, 20).whenComplete((list, error) -> main(() -> {
            if (error != null) { message(player, "거래소를 읽지 못했습니다.", false); return; }
            player.sendMessage(Component.text("거래소 매물", NamedTextColor.GOLD));
            for (ProfileStore.ExchangeListing listing : list) player.sendMessage(Component.text("[" + listing.shortId() + "] " + listing.sellerName() + " · " + displayItem(listing.item()) + " " + listing.quantity() + "개 · 품질 " + listing.quality() + " · 개당 " + listing.unitPrice() + "원", NamedTextColor.GRAY));
            if (list.isEmpty()) player.sendMessage(Component.text("등록된 매물이 없습니다.", NamedTextColor.GRAY));
        }));
    }

    private boolean stall(Player player, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("claim")) {
            int slot = positiveInt(args[2]); store.claimStall(slot, player.getUniqueId(), player.getName()).whenComplete((ignored, error) -> main(() -> { message(player, error == null ? slot + "번 노점을 맡았습니다." : "이미 사용 중인 노점입니다.", error == null); if (error == null) refreshStalls(); })); return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("release")) { store.releaseStall(player.getUniqueId()).whenComplete((ignored, error) -> main(() -> { message(player, error == null ? "노점을 반납했습니다." : "노점 반납 실패", error == null); refreshStalls(); })); return true; }
        return usage(player, "/mp stall claim <1-4> | /mp stall release");
    }

    private boolean guild(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) { showGuild(player); return true; }
        if (args[1].equalsIgnoreCase("create") && args.length >= 3) { String name = join(args, 2); store.createGuild(player.getUniqueId(), player.getName(), name).whenComplete((g, e) -> main(() -> message(player, e == null ? "상단 창설: " + g.name() : "상단 이름이 잘못됐거나 이미 소속돼 있습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("join") && args.length >= 3) { store.joinGuild(player.getUniqueId(), player.getName(), join(args, 2)).whenComplete((g, e) -> main(() -> message(player, e == null ? g.name() + " 상단 가입" : "가입할 수 없습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("leave")) { store.leaveGuild(player.getUniqueId()).whenComplete((v, e) -> main(() -> message(player, e == null ? "상단 탈퇴" : "상단주는 탈퇴할 수 없습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("deposit")) {
            ItemStack hand = player.getInventory().getItemInMainHand(); if (!tradable(hand)) { message(player, "보관할 안전한 아이템을 주손에 드세요.", false); return true; }
            store.guildFor(player.getUniqueId()).whenComplete((guild, error) -> main(() -> { ItemStack current = player.getInventory().getItemInMainHand(); if (error != null || guild.isEmpty()) message(player, "상단 소속이 아닙니다.", false); else if (!tradable(current)) message(player, "보관할 안전한 아이템을 주손에 드세요.", false); else transfer(player, "GUILD", guild.get().id(), itemId(current), 0, quality(current), ignored -> message(player, "공동 창고 보관 완료", true)); })); return true;
        }
        if (args[1].equalsIgnoreCase("warehouse")) { showWarehouse(player); return true; }
        if (args[1].equalsIgnoreCase("withdraw") && args.length == 3) {
            if (!busy.add(player.getUniqueId())) return true; String grant = UUID.randomUUID().toString(); store.withdrawGuildItem(player.getUniqueId(), args[2], grant).whenComplete((item, error) -> main(() -> { busy.remove(player.getUniqueId()); if (error != null) message(player, "창고 물품을 찾지 못했습니다.", false); else { plugin.deliverPendingGrants(player); message(player, "공동 창고 인출 완료", true); } })); return true;
        }
        if (args[1].equalsIgnoreCase("project") && args.length == 4 && args[2].equalsIgnoreCase("money")) {
            long amount = positiveLong(args[3]); PlayerProfile profile = store.get(player.getUniqueId()); if (profile == null || amount < 1) return usage(player, "/mp guild project money <금액>");
            store.contributeGuildMoney(profile, amount).whenComplete((g, e) -> main(() -> message(player, e == null ? project(g) : "기여할 수 없습니다.", e == null))); return true;
        }
        if (args[1].equalsIgnoreCase("project") && args.length == 3 && args[2].equalsIgnoreCase("item")) {
            ItemStack hand = player.getInventory().getItemInMainHand(); String type = hand.getType().name().endsWith("_LOG") ? "LOG" : hand.getType() == Material.RAW_IRON || hand.getType() == Material.IRON_INGOT ? "IRON" : null;
            if (type == null) { message(player, "통나무 또는 철을 주손에 드세요.", false); return true; }
            store.guildFor(player.getUniqueId()).whenComplete((g, e) -> main(() -> { ItemStack current = player.getInventory().getItemInMainHand(); String currentType = current.getType().name().endsWith("_LOG") ? "LOG" : current.getType() == Material.RAW_IRON || current.getType() == Material.IRON_INGOT ? "IRON" : null; if (e != null || g.isEmpty()) message(player, "상단 소속이 아닙니다.", false); else if (currentType == null) message(player, "통나무 또는 철을 주손에 드세요.", false); else transfer(player, "PROJECT", g.get().id(), currentType, 0, 1, ignored -> showGuild(player)); })); return true;
        }
        return usage(player, "/mp guild create|join|leave|status|deposit|warehouse|withdraw|project");
    }

    private void showGuild(Player player) { store.guildFor(player.getUniqueId()).whenComplete((g, e) -> main(() -> message(player, e == null && g.isPresent() ? g.get().name() + " · " + project(g.get()) : "상단 소속이 아닙니다.", e == null && g.isPresent()))); }
    private String project(ProfileStore.Guild g) { ProfileStore.SocialBalance b = store.socialBalance(); return "공동 프로젝트 통나무 " + g.logs() + "/" + b.guildLogs() + " · 철 " + g.iron() + "/" + b.guildIron() + " · 돈 " + g.money() + "/" + b.guildMoney() + " · " + g.projectState(); }
    private void showWarehouse(Player player) { store.guildItems(player.getUniqueId()).whenComplete((items, e) -> main(() -> { if (e != null) { message(player, "공동 창고를 읽지 못했습니다.", false); return; } player.sendMessage(Component.text("상단 공동 창고", NamedTextColor.GOLD)); items.forEach(item -> player.sendMessage(Component.text("[" + item.shortId() + "] " + displayItem(item.item()) + " " + item.quantity() + "개 · 품질 " + item.quality(), NamedTextColor.GRAY))); })); }

    private boolean service(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) { store.services().whenComplete((offers, e) -> main(() -> { if (e != null) { message(player, "서비스를 읽지 못했습니다.", false); return; } player.sendMessage(Component.text("서비스 시장", NamedTextColor.GOLD)); offers.forEach(o -> player.sendMessage(Component.text("[" + o.shortId() + "] " + o.providerName() + " · " + o.type() + " · " + o.price() + "원 · " + o.state(), NamedTextColor.GRAY))); })); return true; }
        if (args[1].equalsIgnoreCase("offer") && args.length == 4) { long price = positiveLong(args[3]); store.createService(player.getUniqueId(), player.getName(), args[2].toUpperCase(Locale.ROOT), price).whenComplete((o, e) -> main(() -> message(player, e == null ? "서비스 등록: " + o.shortId() : "서비스 종류 또는 가격이 잘못됐습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("hire") && args.length == 3) { PlayerProfile profile = store.get(player.getUniqueId()); if (profile == null) return true; store.hireService(profile, args[2]).whenComplete((o, e) -> main(() -> message(player, e == null ? "서비스 계약 완료 · 대금 에스크로 보관" : "서비스를 계약할 수 없습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("submit") && args.length == 3) { store.submitService(player.getUniqueId(), args[2]).whenComplete((o, e) -> main(() -> message(player, e == null ? "완료 제출됨 · 의뢰인 승인 대기" : "제출할 계약이 없습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("approve") && args.length == 3) { PlayerProfile profile = store.get(player.getUniqueId()); if (profile == null) return true; store.approveService(profile, args[2]).whenComplete((o, e) -> main(() -> message(player, e == null ? "승인 완료 · 제공자 정산" : "승인할 계약이 없습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("cancel") && args.length == 3) { store.cancelService(player.getUniqueId(), args[2]).whenComplete((v, e) -> main(() -> message(player, e == null ? "서비스 등록 취소" : "취소할 등록이 없습니다.", e == null))); return true; }
        return usage(player, "/mp service list|offer <종류> <가격>|hire|submit|approve|cancel");
    }

    private boolean restaurant(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("status")) { showRestaurant(player); return true; }
        if (args[1].equalsIgnoreCase("open") && args.length >= 3) { store.openRestaurant(player.getUniqueId(), join(args, 2)).whenComplete((r, e) -> main(() -> message(player, e == null ? "레스토랑 개업: " + r.name() : "개업할 수 없습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("role") && args.length == 4) { Player target = Bukkit.getPlayerExact(args[2]); if (target == null) { message(player, "온라인 플레이어를 찾지 못했습니다.", false); return true; } store.assignRestaurantRole(player.getUniqueId(), target.getUniqueId(), target.getName(), args[3].toUpperCase(Locale.ROOT)).whenComplete((v, e) -> main(() -> message(player, e == null ? target.getName() + " 역할 지정" : "역할 지정 실패", e == null))); return true; }
        if (args[1].equalsIgnoreCase("order")) { store.createRestaurantOrder(player.getUniqueId()).whenComplete((o, e) -> main(() -> message(player, e == null ? "NPC 주문: 농산물·단백질·부재료 각 1묶음" : "진행 중 주문이 있습니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("supply")) {
            ItemStack hand = player.getInventory().getItemInMainHand(); String category = CROP.contains(hand.getType()) ? "CROP" : PROTEIN.contains(hand.getType()) ? "PROTEIN" : EXTRA.contains(hand.getType()) ? "EXTRA" : null;
            if (category == null) { message(player, "농산물·생선/고기·부재료를 주손에 드세요.", false); return true; }
            store.restaurantOrderFor(player.getUniqueId()).whenComplete((order, e) -> main(() -> { ItemStack current = player.getInventory().getItemInMainHand(); String currentCategory = CROP.contains(current.getType()) ? "CROP" : PROTEIN.contains(current.getType()) ? "PROTEIN" : EXTRA.contains(current.getType()) ? "EXTRA" : null; if (e != null || order.isEmpty()) message(player, "진행 중 주문이 없습니다.", false); else if (currentCategory == null) message(player, "농산물·생선/고기·부재료를 주손에 드세요.", false); else transfer(player, "RESTAURANT", order.get().id(), currentCategory, 0, quality(current), ignored -> showRestaurant(player)); })); return true;
        }
        if (Set.of("cook","flip","plate").contains(args[1].toLowerCase(Locale.ROOT))) { store.restaurantAction(player.getUniqueId(), args[1].toUpperCase(Locale.ROOT), System.currentTimeMillis()).whenComplete((o, e) -> main(() -> message(player, e == null ? "조리 단계: " + o.state() + " · 점수 " + o.score() : "지금 수행할 수 없는 조리 단계입니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("serve")) { store.serveRestaurant(player.getUniqueId()).whenComplete((r, e) -> main(() -> message(player, e == null ? "서빙 완료 · " + dishQuality(r.rating()) + " · 평점 " + r.rating() + " · 매출 " + r.reward() + "원" : "서빙할 요리가 없습니다.", e == null))); return true; }
        return usage(player, "/mp restaurant open|role|order|supply|cook|flip|plate|serve|status");
    }

    private void showRestaurant(Player player) { store.restaurantOrderFor(player.getUniqueId()).whenComplete((o, e) -> main(() -> { if (e != null) message(player, "레스토랑 소속이 아닙니다.", false); else if (o.isEmpty()) message(player, "진행 중 NPC 주문이 없습니다.", true); else { ProfileStore.RestaurantOrder r = o.get(); message(player, "주문 " + r.state() + " · 농산물 " + q(r.cropQuality()) + " · 단백질 " + q(r.proteinQuality()) + " · 부재료 " + q(r.extraQuality()) + " · 점수 " + r.score(), true); } })); }

    private void transfer(Player player, String kind, String target, String itemId, long unitPrice, int quality, Consumer<ProfileStore.SocialCompletion> success) {
        if (!busy.add(player.getUniqueId())) return;
        ItemStack hand = player.getInventory().getItemInMainHand(); if (hand.getType().isAir()) { busy.remove(player.getUniqueId()); return; }
        String id = UUID.randomUUID().toString(); ItemStack stored = hand.clone();
        hand.editPersistentDataContainer(data -> data.set(intentKey, PersistentDataType.STRING, id)); player.saveData();
        ProfileStore.SocialIntent intent = new ProfileStore.SocialIntent(id, player.getUniqueId(), kind, target, stored.serializeAsBytes(), itemId, stored.getAmount(), unitPrice, quality, player.getName());
        store.prepareSocialIntent(intent).whenComplete((ignored, prepareError) -> main(() -> {
            if (prepareError != null) { unmark(player, id); busy.remove(player.getUniqueId()); message(player, "다른 사회경제 거래가 진행 중입니다.", false); return; }
            store.markSocialRemoving(id).whenComplete((marked, markError) -> main(() -> {
                if (markError != null || findMarked(player, id) == null) { unmark(player, id); store.cancelSocialIntent(id); busy.remove(player.getUniqueId()); message(player, "아이템 이동이 취소됐습니다.", false); return; }
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); player.saveData();
                store.completeSocialIntent(id).whenComplete((result, completeError) -> main(() -> {
                    busy.remove(player.getUniqueId()); if (completeError != null) { message(player, "거래 저장이 지연됐습니다. 재접속하면 자동 복구됩니다.", false); return; }
                    success.accept(result);
                }));
            }));
        }));
    }

    private void refreshStalls() {
        World world = Bukkit.getWorld("world"); if (world == null) return;
        if (world.getBlockAt(0, HubBuilder.FLOOR_Y - 2, 0).getType() != Material.LODESTONE) return;
        int version = stallRefresh.incrementAndGet();
        store.stalls().whenComplete((claimed, error) -> main(() -> {
            if (error != null || version != stallRefresh.get()) return;
            world.getNearbyEntities(new Location(world, 0, 67, 15), 15, 6, 6).stream().filter(this::isSocialDisplay).forEach(Entity::remove);
            for (int slot = 1; slot <= 4; slot++) {
                int current = slot; ProfileStore.Stall stall = claimed.stream().filter(value -> value.slot() == current).findFirst().orElse(null); Location base = STALLS.get(slot - 1).clone(); base.setWorld(world);
                world.spawn(base, Interaction.class, entity -> { entity.setInteractionWidth(2); entity.setInteractionHeight(2); entity.setResponsive(true); entity.getPersistentDataContainer().set(stallKey, PersistentDataType.INTEGER, current); entity.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true); });
                Location text = base.clone().add(0, 2.2, 0); world.spawn(text, TextDisplay.class, display -> { display.text(Component.text(current + "번 노점\n" + (stall == null ? "빈 자리" : stall.ownerName() + "의 매물\n우클릭"), stall == null ? NamedTextColor.GRAY : NamedTextColor.GOLD)); display.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL); display.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true); });
            }
        }));
    }

    @EventHandler(ignoreCancelled = true) public void onStall(PlayerInteractEntityEvent event) {
        Integer slot = event.getRightClicked().getPersistentDataContainer().get(stallKey, PersistentDataType.INTEGER); if (slot == null) return; event.setCancelled(true);
        store.stalls().whenComplete((stalls, error) -> main(() -> { if (error != null) return; ProfileStore.Stall stall = stalls.stream().filter(value -> value.slot() == slot).findFirst().orElse(null); if (stall == null) message(event.getPlayer(), "빈 노점입니다. /mp stall claim " + slot, false); else listExchange(event.getPlayer(), stall.owner()); }));
    }

    @EventHandler public void onClick(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player && busy.contains(player.getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onDrag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player player && busy.contains(player.getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onHeld(PlayerItemHeldEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }

    private boolean tradable(ItemStack item) { return !item.getType().isAir() && !item.getType().name().contains("SHULKER_BOX") && item.getType() != Material.BUNDLE && item.getType() != Material.FILLED_MAP; }
    private String itemId(ItemStack item) { return item.getPersistentDataContainer().getOrDefault(itemIdKey, PersistentDataType.STRING, item.getType().getKey().toString()); }
    private int quality(ItemStack item) { return Math.max(1, Math.min(5, item.getPersistentDataContainer().getOrDefault(qualityKey, PersistentDataType.INTEGER, 1))); }
    private String displayItem(byte[] item) { try { ItemStack stack = ItemStack.deserializeBytes(item); return stack.hasItemMeta() && stack.getItemMeta().hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName()) : stack.getType().translationKey(); } catch (RuntimeException error) { return "손상된 아이템"; } }
    private ItemStack findMarked(Player player, String id) { for (ItemStack item : player.getInventory().getContents()) if (item != null && id.equals(item.getPersistentDataContainer().get(intentKey, PersistentDataType.STRING))) return item; return null; }
    private void unmark(Player player, String id) { ItemStack item = findMarked(player, id); if (item != null) { item.editPersistentDataContainer(data -> data.remove(intentKey)); player.saveData(); } }
    private void clearOrphanMarks(Player player) { boolean changed = false; for (ItemStack item : player.getInventory().getContents()) if (item != null && item.getPersistentDataContainer().has(intentKey, PersistentDataType.STRING)) { item.editPersistentDataContainer(data -> data.remove(intentKey)); changed = true; } if (changed) player.saveData(); }
    private boolean isSocialDisplay(Entity entity) { return entity.getPersistentDataContainer().has(displayKey, PersistentDataType.BOOLEAN); }
    private String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim(); }
    private long positiveLong(String text) { try { long value = Long.parseLong(text); return value > 0 ? value : -1; } catch (NumberFormatException error) { return -1; } }
    private int positiveInt(String text) { try { int value = Integer.parseInt(text); return value > 0 ? value : -1; } catch (NumberFormatException error) { return -1; } }
    private String q(Integer value) { return value == null ? "없음" : String.valueOf(value); }
    private String dishQuality(int rating) { return rating <= 1 ? "실패" : rating <= 3 ? "보통" : rating == 4 ? "맛있음" : "완벽"; }
    private boolean usage(Player player, String text) { player.sendMessage(Component.text(text, NamedTextColor.YELLOW)); return true; }
    private void message(Player player, String text, boolean success) { player.sendMessage(Component.text(text, success ? NamedTextColor.GREEN : NamedTextColor.RED)); }
    private void main(Runnable runnable) { plugin.getServer().getScheduler().runTask(plugin, runnable); }
}
