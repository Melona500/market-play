package kr.hyuni.marketplay;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

final class HousingManager implements Listener {
    private static final String PREFIX = "mp_house_";
    private final MarketPlayPlugin plugin;
    private final HousingStore store;
    private final NamespacedKey furnitureType;
    private final NamespacedKey furnitureQuality;
    private final NamespacedKey giftIntentKey;
    private final NamespacedKey upgradeIntentKey;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Integer> unloadTasks = new HashMap<>();
    private final Map<String, FurnitureDefinition> definitions = Map.of(
            "bed", new FurnitureDefinition("침대", Material.RED_BED, Map.of(Material.RED_WOOL, 3, Material.OAK_PLANKS, 3)),
            "table", new FurnitureDefinition("식탁", Material.OAK_FENCE, Map.of(Material.OAK_PLANKS, 6)),
            "frame", new FurnitureDefinition("작품 액자", Material.ITEM_FRAME, Map.of(Material.STICK, 8, Material.LEATHER, 1)),
            "workbench", new FurnitureDefinition("작업대", Material.CRAFTING_TABLE, Map.of(Material.OAK_PLANKS, 8)),
            "fireplace", new FurnitureDefinition("벽난로", Material.CAMPFIRE, Map.of(Material.COBBLESTONE, 8, Material.COAL, 2)),
            "aquarium", new FurnitureDefinition("수족관", Material.GLASS, Map.of(Material.GLASS, 8, Material.COD, 1)),
            "royal_decor", new FurnitureDefinition("왕실 수정 장식", Material.AMETHYST_BLOCK, Map.of()));

    HousingManager(MarketPlayPlugin plugin, HousingStore store) {
        this.plugin = plugin;
        this.store = store;
        furnitureType = new NamespacedKey(plugin, "furniture_type");
        furnitureQuality = new NamespacedKey(plugin, "furniture_quality");
        giftIntentKey = new NamespacedKey(plugin, "gift_intent");
        upgradeIntentKey = new NamespacedKey(plugin, "upgrade_intent");
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        store.creatingHouses().whenComplete((houses, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.getLogger().severe("하우징 생성 복구 조회 실패: " + error.getMessage()); return; }
            houses.forEach(house -> prepare(house, null, true));
        }));
    }

    void stop() {
        unloadTasks.values().forEach(Bukkit.getScheduler()::cancelTask);
        Bukkit.getWorlds().stream().filter(this::isHouse).forEach(World::save);
        sessions.clear();
    }

    void recover(Player player, Runnable done) {
        store.activeUpgrade(player.getUniqueId()).whenComplete((active, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { player.kick(Component.text("집 확장 복구 정보를 읽지 못했습니다.")); return; }
            if (active.isPresent()) { recoverUpgrade(player, active.get(), () -> recoverGift(player, done)); return; }
            recoverGift(player, done);
        }));
    }

    private void recoverGift(Player player, Runnable done) {
        store.activeGift(player.getUniqueId()).whenComplete((active, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { player.kick(Component.text("선물 복구 정보를 읽지 못했습니다.")); return; }
            if (active.isEmpty()) { done.run(); return; }
            HousingStore.GiftIntent intent = active.get();
            ItemStack marked = findMarked(player, intent.id());
            if (intent.state().equals("PREPARED") && marked == null) { store.cancelGift(intent.id()).whenComplete((ignored, cancelError) -> Bukkit.getScheduler().runTask(plugin, done)); return; }
            if (intent.state().equals("PREPARED")) store.markGiftRemoving(intent.id()).whenComplete((ignored, markError) -> Bukkit.getScheduler().runTask(plugin, () -> finishGift(player, intent, markError, done)));
            else finishGift(player, intent, null, done);
        }));
    }

    boolean command(Player player, String[] args) {
        if (args.length == 1) { openOwn(player); return true; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "visit" -> { if (args.length == 3) visit(player, args[2]); else help(player); }
            case "leave" -> player.teleportAsync(plugin.lobbyLocation());
            case "visibility" -> { if (args.length == 3) visibility(player, args[2]); else help(player); }
            case "invite" -> { if (args.length == 3) grant(player, args[2], "visit", true, true); else help(player); }
            case "grant" -> { if (args.length == 5) grant(player, args[2], args[3], args[4].equalsIgnoreCase("on"), false); else help(player); }
            case "upgrade" -> upgrade(player);
            case "craft" -> { if (args.length == 3) craft(player, args[2]); else help(player); }
            case "guestbook" -> guestbook(player, args);
            default -> help(player);
        }
        return true;
    }

    boolean mail(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) { listMail(player); return true; }
        if (args[1].equalsIgnoreCase("read") && args.length == 3) {
            store.readMail(player.getUniqueId(), args[2]).whenComplete((ok, error) -> message(player, error == null && ok ? "우편을 읽음 처리했습니다." : "우편을 찾지 못했습니다.", error == null && ok));
            return true;
        }
        if (args[1].equalsIgnoreCase("send") && args.length >= 4) {
            sendLetter(player, args[2], String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            return true;
        }
        if (args[1].equalsIgnoreCase("gift") && args.length >= 3) {
            sendGift(player, args[2], args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "선물이 도착했습니다.");
            return true;
        }
        player.sendMessage(Component.text("/mp mail [list|read <번호>|send <플레이어> <편지>|gift <플레이어> [편지]]", NamedTextColor.AQUA));
        return true;
    }

    private void openOwn(Player player) {
        store.ensureHouse(player.getUniqueId(), player.getName()).whenComplete((house, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) message(player, "집 정보를 만들지 못했습니다.", false);
            else prepare(house, player, false);
        }));
    }

    private void visit(Player player, String ownerName) {
        store.houseByName(ownerName).whenComplete((house, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || house.isEmpty()) message(player, "공개된 집을 찾지 못했습니다.", false);
            else prepare(house.get(), player, false);
        }));
    }

    private void prepare(HousingStore.House house, Player visitor, boolean unloadAfter) {
        store.permissions(house.owner()).thenCombine(store.furniture(house.owner()), (permissions, rows) -> new Session(house, permissions, rows))
                .whenComplete((session, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) { if (visitor != null) message(visitor, "집 권한을 불러오지 못했습니다.", false); return; }
                    if (visitor != null && !canVisit(session, visitor)) { message(visitor, "이 집은 방문할 수 없습니다.", false); return; }
                    boolean newWorld = !java.nio.file.Files.isRegularFile(Bukkit.getWorldContainer().toPath().resolve(house.worldName()).resolve("level.dat"));
                    World world = loadWorld(session.house());
                    if (world == null) { if (visitor != null) message(visitor, "집 월드를 열지 못했습니다.", false); return; }
                    sessions.put(world.getName(), session);
                    cancelUnload(world.getName());
                    if (newWorld) buildAll(world, house.level());
                    else if (house.state().equals("CREATING")) buildLevel(world, house.level());
                    protectGsit(world);
                    if (house.state().equals("CREATING")) {
                        store.markReady(house.owner());
                        HousingStore.House ready = new HousingStore.House(house.owner(), house.ownerName(), house.worldName(), house.level(), house.visibility(), "READY", Instant.now());
                        sessions.put(world.getName(), new Session(ready, session.permissions(), new ArrayList<>(session.furniture().values())));
                    }
                    if (visitor != null) {
                        visitor.teleportAsync(new Location(world, 0.5, 6, 4.5));
                        message(visitor, house.ownerName() + "님의 집 Lv." + house.level(), true);
                    } else if (unloadAfter) scheduleUnload(world);
                }));
    }

    private World loadWorld(HousingStore.House house) {
        World world = Bukkit.getWorld(house.worldName());
        if (world == null) world = new WorldCreator(house.worldName()).type(WorldType.FLAT).generateStructures(false).createWorld();
        if (world == null) return null;
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(32 + (house.level() - 1) * 16);
        world.setSpawnLocation(0, 6, 4);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        return world;
    }

    private boolean canVisit(Session session, Player player) {
        if (session.house().owner().equals(player.getUniqueId())) return true;
        int flags = session.permissions().getOrDefault(player.getUniqueId(), 0);
        return session.house().visibility().equals("public") || session.house().visibility().equals("invite") && (flags & HousePermission.VISIT.bit) != 0;
    }

    private boolean allows(Player player, World world, HousePermission permission) {
        Session session = sessions.get(world.getName());
        if (session == null) return false;
        return session.house().owner().equals(player.getUniqueId()) || (session.permissions().getOrDefault(player.getUniqueId(), 0) & permission.bit) != 0;
    }

    private void visibility(Player player, String value) {
        store.visibility(player.getUniqueId(), value.toLowerCase(Locale.ROOT)).whenComplete((house, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { message(player, "공개 설정: private, invite, public", false); return; }
            refreshSession(house);
            message(player, "집 공개 설정: " + house.visibility(), true);
        }));
    }

    private void grant(Player owner, String name, String permissionName, boolean enabled, boolean inviteMail) {
        HousePermission permission;
        try { permission = HousePermission.valueOf(permissionName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { message(owner, "권한: visit, furniture, food, storage, place, build, invite", false); return; }
        Session current = sessions.get(owner.getWorld().getName());
        UUID houseOwner = current != null && inviteMail && allows(owner, owner.getWorld(), HousePermission.INVITE) ? current.house().owner() : owner.getUniqueId();
        if (!inviteMail && current != null && !current.house().owner().equals(owner.getUniqueId())) { message(owner, "집주인만 세부 권한을 바꿀 수 있습니다.", false); return; }
        store.houseByOwner(houseOwner).thenCombine(store.playerId(name), (house, id) -> Map.entry(house, id)).thenCompose(pair ->
                pair.getKey().isEmpty() || pair.getValue().isEmpty() ? CompletableFuture.failedFuture(new IllegalArgumentException()) :
                store.permission(houseOwner, pair.getValue().get(), permission, enabled).thenApply(flags -> Map.entry(pair.getValue().get(), flags)))
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) { message(owner, "접속 기록이 있는 플레이어를 찾지 못했습니다.", false); return; }
                    sessions.values().stream().filter(session -> session.house().owner().equals(houseOwner)).findFirst()
                            .ifPresent(session -> session.permissions().put(result.getKey(), result.getValue()));
                    message(owner, name + "의 " + permission.name().toLowerCase(Locale.ROOT) + ": " + (enabled ? "허용" : "거부"), true);
                    if (inviteMail && enabled) store.sendMail(owner.getUniqueId(), owner.getName(), result.getKey(), "INVITE", (current == null ? owner.getName() : current.house().ownerName()) + "님의 집에 초대되었습니다.", null);
                }));
    }

    private void upgrade(Player player) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        Session session = sessions.get(player.getWorld().getName());
        if (profile == null || session == null || !session.house().owner().equals(player.getUniqueId())) { message(player, "자신의 집 안에서만 확장할 수 있습니다.", false); return; }
        int next = session.house().level() + 1;
        if (next > 5) { message(player, "이미 최고 단계 저택입니다.", false); return; }
        UpgradeRequirement requirement = upgradeRequirement(next);
        if (!hasMaterials(player, requirement.materials())) { message(player, "확장 재료 부족: " + requirement.materials(), false); return; }
        plugin.lock(player.getUniqueId());
        String intentId = UUID.randomUUID().toString();
        store.prepareUpgrade(player.getUniqueId(), next, intentId, refundItems(requirement.materials())).whenComplete((intent, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.unlock(player.getUniqueId()); message(player, "집 확장을 준비하지 못했습니다.", false); return; }
            if (!reserveMaterials(player, requirement.materials(), intent.id())) { store.cancelUpgrade(intent.id()); plugin.unlock(player.getUniqueId()); message(player, "확장 재료가 바뀌었습니다.", false); return; }
            player.saveData();
            store.markUpgradeRemoving(intent.id()).whenComplete((ignored, markError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (markError != null) { player.kick(Component.text("집 확장 제거 상태를 저장하지 못했습니다.")); return; }
                removeMarked(player, upgradeIntentKey, intent.id());
                player.saveData();
                completeUpgrade(player, intent, () -> plugin.unlock(player.getUniqueId()));
            }));
        }));
    }

    private void recoverUpgrade(Player player, HousingStore.UpgradeIntent intent, Runnable done) {
        boolean marked = findMarked(player, upgradeIntentKey, intent.id()) != null;
        if (intent.state().equals("PREPARED") && !marked) { store.cancelUpgrade(intent.id()).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, done)); return; }
        Runnable remove = () -> {
            removeMarked(player, upgradeIntentKey, intent.id());
            player.saveData();
            completeUpgrade(player, intent, done);
        };
        if (intent.state().equals("PREPARED")) store.markUpgradeRemoving(intent.id()).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) player.kick(Component.text("집 확장 복구 상태를 저장하지 못했습니다."));
            else remove.run();
        }));
        else remove.run();
    }

    private void completeUpgrade(Player player, HousingStore.UpgradeIntent intent, Runnable done) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        UpgradeRequirement requirement = upgradeRequirement(intent.targetLevel());
        store.upgrade(profile, intent.targetLevel() - 1, requirement.price(), requirement.innerPower(), requirement.carpentryXp(), requirement.furnitureTypes(), intent.id())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        store.refundUpgrade(intent.id()).whenComplete((ignored, refundError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (refundError != null) { player.kick(Component.text("집 확장 재료 환불에 실패했습니다.")); return; }
                            plugin.deliverPendingGrants(player);
                            message(player, "확장 조건이 부족해 재료를 돌려드립니다.", false);
                            done.run();
                        }));
                        return;
                    }
                    store.finishUpgrade(intent.id()).whenComplete((ignored, finishError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (finishError != null) { player.kick(Component.text("집 확장 완료 상태를 저장하지 못했습니다.")); return; }
                        synchronized (profile) { profile.setMoney(result.balance()); }
                        plugin.saveProfile(profile);
                        HousingStore.House old = sessions.values().stream().filter(value -> value.house().owner().equals(player.getUniqueId())).map(Session::house).findFirst()
                                .orElse(new HousingStore.House(player.getUniqueId(), player.getName(), "mp_house_" + player.getUniqueId().toString().replace("-", ""), result.level() - 1, "private", "READY", Instant.now()));
                        HousingStore.House upgraded = new HousingStore.House(old.owner(), old.ownerName(), old.worldName(), result.level(), old.visibility(), "CREATING", Instant.now());
                        World world = Bukkit.getWorld(upgraded.worldName());
                        if (world != null) {
                            buildLevel(world, result.level());
                            world.getWorldBorder().setSize(32 + (result.level() - 1) * 16);
                            store.markReady(upgraded.owner());
                            upgraded = new HousingStore.House(upgraded.owner(), upgraded.ownerName(), upgraded.worldName(), upgraded.level(), upgraded.visibility(), "READY", Instant.now());
                            refreshSession(upgraded);
                        }
                        store.sendMail(null, "왕실 건축국", player.getUniqueId(), "NPC", "집 Lv." + result.level() + " 확장이 완료되었습니다.", null);
                        message(player, "집이 Lv." + result.level() + "로 확장되었습니다.", true);
                        done.run();
                    }));
                });
    }

    private UpgradeRequirement upgradeRequirement(int level) {
        String path = "housing.upgrades." + level;
        Map<Material, Integer> materials = new EnumMap<>(Material.class);
        var section = plugin.getConfig().getConfigurationSection(path + ".materials");
        if (section != null) for (String key : section.getKeys(false)) materials.put(Material.valueOf(key), section.getInt(key));
        return new UpgradeRequirement(plugin.getConfig().getLong(path + ".price"), plugin.getConfig().getLong(path + ".inner-power"),
                plugin.getConfig().getLong(path + ".carpentry-xp"), plugin.getConfig().getInt(path + ".furniture-types"), materials);
    }

    private void craft(Player player, String type) {
        FurnitureDefinition definition = definitions.get(type.toLowerCase(Locale.ROOT));
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (definition == null || profile == null) { message(player, "가구: bed, table, frame, workbench, fireplace, aquarium", false); return; }
        List<ItemStack> removed = removeMaterials(player, definition.materials());
        if (removed == null) { message(player, "가구 재료 부족: " + definition.materials(), false); return; }
        int roll = ThreadLocalRandom.current().nextInt(100) + profile.level(Skill.CARPENTRY) * 3;
        String quality = roll >= 110 ? "명품" : roll >= 75 ? "고급" : "보통";
        ItemStack item = new ItemStack(definition.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(quality + " " + definition.name(), NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(furnitureType, PersistentDataType.STRING, type.toLowerCase(Locale.ROOT));
            meta.getPersistentDataContainer().set(furnitureQuality, PersistentDataType.STRING, quality);
        });
        restoreItems(player, List.of(item));
        profile.addExperience(Skill.CARPENTRY, 5);
        plugin.saveProfile(profile);
        message(player, quality + " " + definition.name() + " 제작 완료", true);
    }

    ItemStack furnitureItem(String type, String quality) {
        FurnitureDefinition definition = definitions.get(type);
        if (definition == null) throw new IllegalArgumentException("Unknown furniture: " + type);
        ItemStack item = new ItemStack(definition.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(quality + " " + definition.name(), NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(furnitureType, PersistentDataType.STRING, type);
            meta.getPersistentDataContainer().set(furnitureQuality, PersistentDataType.STRING, quality);
        });
        return item;
    }

    private void guestbook(Player player, String[] args) {
        Session session = sessions.get(player.getWorld().getName());
        if (session == null) { message(player, "집 안에서 사용하세요.", false); return; }
        if (args.length == 2 || args[2].equalsIgnoreCase("list")) {
            store.guestbook(session.house().owner()).whenComplete((entries, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) { message(player, "방명록을 읽지 못했습니다.", false); return; }
                entries.forEach(entry -> player.sendMessage(Component.text("[" + entry.shortId() + "] " + entry.authorName() + ": " + entry.body() + (entry.reported() ? " [신고됨]" : ""), NamedTextColor.GRAY)));
            }));
            return;
        }
        if (args[2].equalsIgnoreCase("write") && args.length >= 4) {
            store.writeGuestbook(session.house().owner(), player.getUniqueId(), player.getName(), String.join(" ", Arrays.copyOfRange(args, 3, args.length)))
                    .whenComplete((entry, error) -> message(player, error == null ? "방명록 등록: " + entry.shortId() : "방명록을 작성할 수 없습니다.", error == null));
            return;
        }
        if (args.length == 4 && args[2].equalsIgnoreCase("report")) { store.reportGuestbook(session.house().owner(), args[3]).whenComplete((ok, error) -> message(player, error == null && ok ? "신고했습니다." : "글을 찾지 못했습니다.", error == null && ok)); return; }
        if (!session.house().owner().equals(player.getUniqueId())) { message(player, "집주인만 삭제·차단할 수 있습니다.", false); return; }
        if (args.length == 4 && args[2].equalsIgnoreCase("remove")) { store.deleteGuestbook(session.house().owner(), args[3]).whenComplete((ok, error) -> message(player, error == null && ok ? "삭제했습니다." : "글을 찾지 못했습니다.", error == null && ok)); return; }
        if (args.length == 4 && args[2].equalsIgnoreCase("block")) store.playerId(args[3]).thenCompose(id -> id.isEmpty() ? CompletableFuture.failedFuture(new IllegalArgumentException()) : store.blockGuest(session.house().owner(), id.get()))
                .whenComplete((ignored, error) -> message(player, error == null ? "방명록 작성을 차단했습니다." : "플레이어를 찾지 못했습니다.", error == null));
    }

    private void sendLetter(Player sender, String recipient, String body) {
        store.playerId(recipient).thenCompose(id -> id.isEmpty() ? CompletableFuture.failedFuture(new IllegalArgumentException()) :
                store.sendMail(sender.getUniqueId(), sender.getName(), id.get(), "LETTER", body, null))
                .whenComplete((mail, error) -> message(sender, error == null ? "편지를 보냈습니다: " + mail.shortId() : "편지를 보내지 못했습니다.", error == null));
    }

    private void sendGift(Player sender, String recipientName, String body) {
        ItemStack held = sender.getInventory().getItemInMainHand();
        if (held.getType().isAir()) { message(sender, "주 손에 선물을 드세요.", false); return; }
        plugin.lock(sender.getUniqueId());
        String grantId = UUID.randomUUID().toString();
        ItemStack gift = held.clone();
        gift.editMeta(meta -> meta.getPersistentDataContainer().set(plugin.grantKey(), PersistentDataType.STRING, grantId));
        store.playerId(recipientName).thenCompose(id -> id.isEmpty() ? CompletableFuture.failedFuture(new IllegalArgumentException()) :
                store.prepareGift(sender.getUniqueId(), sender.getName(), id.get(), body, grantId, gift.serializeAsBytes()))
                .whenComplete((intent, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !same(sender.getInventory().getItemInMainHand(), held)) {
                        if (intent != null) store.cancelGift(intent.id());
                        plugin.unlock(sender.getUniqueId());
                        message(sender, "선물을 준비하지 못했습니다.", false);
                        return;
                    }
                    sender.getInventory().getItemInMainHand().editMeta(meta -> meta.getPersistentDataContainer().set(giftIntentKey, PersistentDataType.STRING, intent.id()));
                    sender.saveData();
                    store.markGiftRemoving(intent.id()).whenComplete((ignored, markError) -> Bukkit.getScheduler().runTask(plugin, () -> finishGift(sender, intent, markError, () -> plugin.unlock(sender.getUniqueId()))));
                }));
    }

    private void finishGift(Player sender, HousingStore.GiftIntent intent, Throwable error, Runnable done) {
        if (error != null) { sender.kick(Component.text("선물 제거 상태를 저장하지 못했습니다.")); return; }
        ItemStack marked = findMarked(sender, intent.id());
        if (marked != null) {
            marked.setAmount(0);
            sender.saveData();
        }
        store.completeGift(intent.id()).whenComplete((mail, completeError) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (completeError != null) { sender.kick(Component.text("선물 전달을 완료하지 못했습니다. 재접속하면 복구됩니다.")); return; }
            Player recipient = Bukkit.getPlayer(intent.recipient());
            if (recipient != null) plugin.deliverPendingGrants(recipient);
            message(sender, "선물을 보냈습니다: " + mail.shortId(), true);
            done.run();
        }));
    }

    private ItemStack findMarked(Player player, String intentId) {
        for (ItemStack item : player.getInventory().getContents()) if (item != null && intentId.equals(item.getPersistentDataContainer().get(giftIntentKey, PersistentDataType.STRING))) return item;
        return null;
    }

    private void listMail(Player player) {
        store.mail(player.getUniqueId()).whenComplete((mail, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { message(player, "우편을 읽지 못했습니다.", false); return; }
            mail.forEach(entry -> player.sendMessage(Component.text("[" + entry.shortId() + "] " + (entry.read() ? "" : "새 ") + entry.kind() + " · " + entry.senderName() + ": " + entry.body(), entry.read() ? NamedTextColor.GRAY : NamedTextColor.GOLD)));
        }));
    }

    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent event) {
        if (!isHouse(event.getBlock().getWorld())) return;
        String type = event.getItemInHand().getPersistentDataContainer().get(furnitureType, PersistentDataType.STRING);
        if (!allows(event.getPlayer(), event.getBlock().getWorld(), type == null ? HousePermission.BUILD : HousePermission.PLACE)) { event.setCancelled(true); deny(event.getPlayer()); return; }
        if (type != null) {
            String quality = event.getItemInHand().getPersistentDataContainer().getOrDefault(furnitureQuality, PersistentDataType.STRING, "보통");
            Session session = sessions.get(event.getBlock().getWorld().getName());
            HousingStore.Furniture placed = new HousingStore.Furniture(event.getBlock().getWorld().getName(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ(), type, quality);
            session.furniture().put(placed.key(), placed);
            store.putFurniture(session.house().owner(), placed.world(), placed.x(), placed.y(), placed.z(), type, quality, event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent event) {
        if (!isHouse(event.getBlock().getWorld())) return;
        Session session = sessions.get(event.getBlock().getWorld().getName());
        String key = key(event.getBlock());
        HousePermission permission = session != null && session.furniture().containsKey(key) ? HousePermission.PLACE : HousePermission.BUILD;
        if (!allows(event.getPlayer(), event.getBlock().getWorld(), permission)) { event.setCancelled(true); deny(event.getPlayer()); return; }
        if (session != null && session.furniture().remove(key) != null) store.removeFurniture(event.getBlock().getWorld().getName(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !isHouse(block.getWorld())) return;
        Session session = sessions.get(block.getWorld().getName());
        HousingStore.Furniture placed = session == null ? null : session.furniture().get(key(block));
        if (placed != null && placed.type().equals("table")) {
            if (!allows(event.getPlayer(), block.getWorld(), HousePermission.FOOD)) { event.setCancelled(true); deny(event.getPlayer()); return; }
            meal(event.getPlayer(), block.getLocation()); event.setCancelled(true); return;
        }
        HousePermission permission = block.getState() instanceof Container ? HousePermission.STORAGE : HousePermission.FURNITURE;
        if (!allows(event.getPlayer(), block.getWorld(), permission)) { event.setCancelled(true); deny(event.getPlayer()); }
    }

    @EventHandler(ignoreCancelled = true) public void onInventory(InventoryOpenEvent event) {
        Location location = event.getInventory().getLocation();
        if (!(event.getPlayer() instanceof Player player) || location == null || !isHouse(location.getWorld())) return;
        if (!allows(player, location.getWorld(), HousePermission.STORAGE)) { event.setCancelled(true); deny(player); }
    }

    @EventHandler(ignoreCancelled = true) public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !isHouse(event.getEntity().getWorld())) return;
        if (!allows(player, event.getEntity().getWorld(), HousePermission.PLACE)) { event.setCancelled(true); deny(player); return; }
        ItemStack item = event.getItemStack();
        String type = item.getPersistentDataContainer().get(furnitureType, PersistentDataType.STRING);
        if (type == null) return;
        String quality = item.getPersistentDataContainer().getOrDefault(furnitureQuality, PersistentDataType.STRING, "보통");
        Location location = event.getEntity().getLocation();
        Session session = sessions.get(location.getWorld().getName());
        HousingStore.Furniture placed = new HousingStore.Furniture(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), type, quality);
        session.furniture().put(placed.key(), placed);
        store.putFurniture(session.house().owner(), placed.world(), placed.x(), placed.y(), placed.z(), type, quality, player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true) public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player) || !isHouse(event.getEntity().getWorld())) return;
        if (!allows(player, event.getEntity().getWorld(), HousePermission.PLACE)) { event.setCancelled(true); deny(player); return; }
        Location location = event.getEntity().getLocation();
        Session session = sessions.get(location.getWorld().getName());
        HousingStore.Furniture placed = session.furniture().remove(location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ());
        if (placed == null) return;
        event.setCancelled(true);
        event.getEntity().remove();
        store.removeFurniture(placed.world(), placed.x(), placed.y(), placed.z());
        FurnitureDefinition definition = definitions.get(placed.type());
        ItemStack item = new ItemStack(definition.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(placed.quality() + " " + definition.name(), NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(furnitureType, PersistentDataType.STRING, placed.type());
            meta.getPersistentDataContainer().set(furnitureQuality, PersistentDataType.STRING, placed.quality());
        });
        restoreItems(player, List.of(item));
    }
    @EventHandler(ignoreCancelled = true) public void onArmorStand(PlayerArmorStandManipulateEvent event) { if (isHouse(event.getRightClicked().getWorld()) && !allows(event.getPlayer(), event.getRightClicked().getWorld(), HousePermission.FURNITURE)) { event.setCancelled(true); deny(event.getPlayer()); } }
    @EventHandler(ignoreCancelled = true) public void onEntity(PlayerInteractEntityEvent event) { if (isHouse(event.getRightClicked().getWorld()) && (event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand) && !allows(event.getPlayer(), event.getRightClicked().getWorld(), HousePermission.FURNITURE)) { event.setCancelled(true); deny(event.getPlayer()); } }
    @EventHandler(ignoreCancelled = true) public void onBucket(PlayerBucketEmptyEvent event) { if (isHouse(event.getBlock().getWorld()) && !allows(event.getPlayer(), event.getBlock().getWorld(), HousePermission.BUILD)) { event.setCancelled(true); deny(event.getPlayer()); } }
    @EventHandler(ignoreCancelled = true) public void onBucket(PlayerBucketFillEvent event) { if (isHouse(event.getBlock().getWorld()) && !allows(event.getPlayer(), event.getBlock().getWorld(), HousePermission.BUILD)) { event.setCancelled(true); deny(event.getPlayer()); } }
    @EventHandler(ignoreCancelled = true) public void onEntityPlace(EntityPlaceEvent event) { if (event.getPlayer() != null && isHouse(event.getEntity().getWorld()) && !allows(event.getPlayer(), event.getEntity().getWorld(), HousePermission.PLACE)) { event.setCancelled(true); deny(event.getPlayer()); } }
    @EventHandler(ignoreCancelled = true) public void onEntityDamage(EntityDamageByEntityEvent event) { if (event.getDamager() instanceof Player player && isHouse(event.getEntity().getWorld()) && (event.getEntity() instanceof Hanging || event.getEntity() instanceof ArmorStand) && !allows(player, event.getEntity().getWorld(), HousePermission.PLACE)) { event.setCancelled(true); deny(player); } }
    @EventHandler(ignoreCancelled = true) public void onIgnite(BlockIgniteEvent event) { if (event.getPlayer() != null && isHouse(event.getBlock().getWorld()) && !allows(event.getPlayer(), event.getBlock().getWorld(), HousePermission.BUILD)) { event.setCancelled(true); deny(event.getPlayer()); } }
    @EventHandler public void onExplode(BlockExplodeEvent event) { if (isHouse(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || !isHouse(event.getTo().getWorld())) return;
        Session session = sessions.get(event.getTo().getWorld().getName());
        if (session == null || !canVisit(session, event.getPlayer())) { event.setCancelled(true); deny(event.getPlayer()); }
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        if (!isHouse(event.getRespawnLocation().getWorld())) return;
        Session session = sessions.get(event.getRespawnLocation().getWorld().getName());
        if (session == null || !canVisit(session, event.getPlayer())) event.setRespawnLocation(plugin.lobbyLocation());
    }
    @EventHandler public void onChangedWorld(PlayerChangedWorldEvent event) { if (isHouse(event.getFrom())) scheduleUnload(event.getFrom()); if (isHouse(event.getPlayer().getWorld())) cancelUnload(event.getPlayer().getWorld().getName()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { World world = event.getPlayer().getWorld(); if (isHouse(world)) Bukkit.getScheduler().runTask(plugin, () -> scheduleUnload(world)); }
    @EventHandler public void onUnload(WorldUnloadEvent event) { if (isHouse(event.getWorld())) { sessions.remove(event.getWorld().getName()); cancelUnload(event.getWorld().getName()); } }

    private void meal(Player host, Location table) {
        ItemStack food = host.getInventory().getItemInMainHand();
        if (!food.getType().isEdible()) { message(host, "주 손에 음식을 들고 식탁을 사용하세요.", false); return; }
        List<Player> diners = table.getNearbyPlayers(4).stream().filter(player -> plugin.profile(player.getUniqueId()) != null).toList();
        if (diners.size() < 2) { message(host, "두 명 이상 모여야 공동 식사를 할 수 있습니다.", false); return; }
        Session session = sessions.get(host.getWorld().getName());
        store.claimMeal(session.house().owner(), host.getUniqueId(), Instant.now(), java.time.Duration.ofMinutes(10)).whenComplete((allowed, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !allowed) { message(host, "공동 식사는 10분마다 이용할 수 있습니다.", false); return; }
            serveMeal(host, table);
        }));
    }

    private void serveMeal(Player host, Location table) {
        ItemStack food = host.getInventory().getItemInMainHand();
        if (!food.getType().isEdible()) { message(host, "음식이 바뀌었습니다.", false); return; }
        List<Player> diners = table.getNearbyPlayers(4).stream().filter(player -> plugin.profile(player.getUniqueId()) != null).toList();
        if (diners.size() < 2) { message(host, "식사 인원이 떠났습니다.", false); return; }
        food.subtract();
        for (Player diner : diners) {
            PlayerProfile profile = plugin.profile(diner.getUniqueId());
            profile.restoreVitality(10, plugin.maximumVitality());
            plugin.saveProfile(profile);
            diner.sendActionBar(Component.text("공동 식사: 활력 +10", NamedTextColor.GREEN));
        }
        host.saveData();
    }

    private void scheduleUnload(World world) {
        if (!world.getPlayers().isEmpty() || unloadTasks.containsKey(world.getName())) return;
        int task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            unloadTasks.remove(world.getName());
            if (!world.getPlayers().isEmpty()) return;
            Session session = sessions.get(world.getName());
            world.save();
            if (session != null) store.touch(session.house().owner());
            Bukkit.unloadWorld(world, true);
        }, 20L * 60L * 5L).getTaskId();
        unloadTasks.put(world.getName(), task);
    }

    private void cancelUnload(String world) { Integer task = unloadTasks.remove(world); if (task != null) Bukkit.getScheduler().cancelTask(task); }
    private void refreshSession(HousingStore.House house) { Session old = sessions.get(house.worldName()); if (old != null) sessions.put(house.worldName(), new Session(house, old.permissions(), new ArrayList<>(old.furniture().values()))); }

    private void protectGsit(World world) {
        var regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regions == null) return;
        var global = regions.getRegion("__global__");
        if (global == null) { global = new GlobalProtectedRegion("__global__"); regions.addRegion(global); }
        for (String name : List.of("sit", "playersit", "pose", "crawl")) {
            var flag = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (flag instanceof StateFlag state) global.setFlag(state, StateFlag.State.ALLOW);
            else plugin.getLogger().warning("GSit 플래그 없음: " + name);
        }
        try { regions.save(); } catch (Exception error) { plugin.getLogger().severe("하우징 GSit 보호 저장 실패: " + error.getMessage()); }
    }

    private void buildAll(World world, int level) { for (int current = 1; current <= level; current++) buildLevel(world, current); }

    private void buildLevel(World world, int level) {
        if (level == 1) { room(world, -6, 6, -6, 6, 5, Material.SPRUCE_PLANKS, Material.RED_MUSHROOM_BLOCK); world.getBlockAt(-3, 6, 0).setType(Material.RED_BED); world.getBlockAt(3, 6, 0).setType(Material.CHEST); }
        if (level == 2) { room(world, 7, 17, -6, 6, 5, Material.OAK_PLANKS, Material.DARK_OAK_PLANKS); world.getBlockAt(12, 6, 0).setType(Material.SMOKER); }
        if (level == 3) { room(world, -17, -7, -6, 6, 5, Material.BIRCH_PLANKS, Material.MOSS_BLOCK); world.getBlockAt(-12, 6, 0).setType(Material.LOOM); garden(world); }
        if (level == 4) { room(world, -6, 6, -17, -7, 5, Material.DARK_OAK_PLANKS, Material.PURPUR_BLOCK); room(world, -6, 6, -6, 6, 11, Material.SPRUCE_PLANKS, Material.RED_MUSHROOM_BLOCK); world.getBlockAt(0, 6, -10).setType(Material.JUKEBOX); }
        if (level == 5) { room(world, -8, 8, 7, 19, 5, Material.QUARTZ_BLOCK, Material.GLASS); for (int x = -4; x <= 4; x++) { world.getBlockAt(x, 6, 14).setType(Material.GLASS); world.getBlockAt(x, 7, 14).setType(Material.WATER); } }
        world.save();
    }

    private void room(World world, int minX, int maxX, int minZ, int maxZ, int y, Material floor, Material roof) {
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            world.getBlockAt(x, y, z).setType(floor);
            if (world.getBlockAt(x, y + 6, z).isEmpty()) world.getBlockAt(x, y + 6, z).setType(roof);
            if (x == minX || x == maxX || z == minZ || z == maxZ) for (int dy = 1; dy <= 5; dy++)
                if (world.getBlockAt(x, y + dy, z).isEmpty()) world.getBlockAt(x, y + dy, z).setType(dy == 3 && (x + z) % 4 == 0 ? Material.GLASS_PANE : Material.STRIPPED_SPRUCE_LOG);
        }
        world.getBlockAt(0, y + 1, maxZ).setType(Material.AIR);
        world.getBlockAt(0, y + 2, maxZ).setType(Material.AIR);
    }

    private void garden(World world) { for (int x = -16; x <= -8; x += 2) for (int z = 8; z <= 14; z += 2) { world.getBlockAt(x, 4, z).setType(Material.FARMLAND); world.getBlockAt(x, 5, z).setType(Material.WHEAT); } }

    private List<ItemStack> removeMaterials(Player player, Map<Material, Integer> required) {
        for (var entry : required.entrySet()) if (!player.getInventory().containsAtLeast(new ItemStack(entry.getKey()), entry.getValue())) return null;
        List<ItemStack> removed = new ArrayList<>();
        for (var entry : required.entrySet()) {
            int left = entry.getValue();
            for (int slot = 0; slot < player.getInventory().getSize() && left > 0; slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                if (item == null || item.getType() != entry.getKey()) continue;
                int take = Math.min(left, item.getAmount());
                ItemStack copy = item.clone(); copy.setAmount(take); removed.add(copy);
                item.subtract(take); left -= take;
            }
        }
        return removed;
    }

    private boolean hasMaterials(Player player, Map<Material, Integer> required) {
        return required.entrySet().stream().allMatch(entry -> player.getInventory().containsAtLeast(new ItemStack(entry.getKey()), entry.getValue()));
    }

    private List<HousingStore.RefundItem> refundItems(Map<Material, Integer> required) {
        List<HousingStore.RefundItem> result = new ArrayList<>();
        for (var entry : required.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                int amount = Math.min(left, entry.getKey().getMaxStackSize());
                String grantId = UUID.randomUUID().toString();
                ItemStack item = new ItemStack(entry.getKey(), amount);
                item.editMeta(meta -> meta.getPersistentDataContainer().set(plugin.grantKey(), PersistentDataType.STRING, grantId));
                result.add(new HousingStore.RefundItem(grantId, item.serializeAsBytes()));
                left -= amount;
            }
        }
        return result;
    }

    private boolean reserveMaterials(Player player, Map<Material, Integer> required, String intentId) {
        List<ItemStack> removed = removeMaterials(player, required);
        if (removed == null) return false;
        for (ItemStack item : removed) item.editMeta(meta -> meta.getPersistentDataContainer().set(upgradeIntentKey, PersistentDataType.STRING, intentId));
        Map<Integer, ItemStack> overflow = new LinkedHashMap<>();
        for (ItemStack item : removed) overflow.putAll(player.getInventory().addItem(item));
        if (overflow.isEmpty()) return true;
        removeMarked(player, upgradeIntentKey, intentId);
        for (ItemStack item : removed) {
            item.editMeta(meta -> meta.getPersistentDataContainer().remove(upgradeIntentKey));
            player.getInventory().addItem(item);
        }
        return false;
    }

    private void removeMarked(Player player, NamespacedKey key, String value) {
        for (ItemStack item : player.getInventory().getContents()) if (item != null && value.equals(item.getPersistentDataContainer().get(key, PersistentDataType.STRING))) item.setAmount(0);
    }

    private ItemStack findMarked(Player player, NamespacedKey key, String value) {
        for (ItemStack item : player.getInventory().getContents()) if (item != null && value.equals(item.getPersistentDataContainer().get(key, PersistentDataType.STRING))) return item;
        return null;
    }

    private void restoreItems(Player player, List<ItemStack> items) { for (ItemStack item : items) for (ItemStack overflow : player.getInventory().addItem(item).values()) player.getWorld().dropItemNaturally(player.getLocation(), overflow); player.saveData(); }
    private boolean same(ItemStack a, ItemStack b) { return a.getType() == b.getType() && a.getAmount() == b.getAmount() && a.isSimilar(b); }
    private String key(Block block) { return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ(); }
    private boolean isHouse(World world) { return world != null && world.getName().startsWith(PREFIX); }
    private void deny(Player player) { player.sendActionBar(Component.text("이 집에서 해당 권한이 없습니다.", NamedTextColor.RED)); }
    private void help(Player player) { player.sendMessage(Component.text("/mp home [visit|leave|visibility|invite|grant|upgrade|craft|guestbook] · /mp mail", NamedTextColor.AQUA)); }
    private void message(Player player, String text, boolean success) { Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) player.sendMessage(Component.text(text, success ? NamedTextColor.GREEN : NamedTextColor.RED)); }); }

    private record Session(HousingStore.House house, Map<UUID, Integer> permissions, Map<String, HousingStore.Furniture> furniture) {
        Session(HousingStore.House house, Map<UUID, Integer> permissions, List<HousingStore.Furniture> rows) { this(house, new HashMap<>(permissions), index(rows)); }
        private static Map<String, HousingStore.Furniture> index(List<HousingStore.Furniture> rows) { Map<String, HousingStore.Furniture> result = new HashMap<>(); rows.forEach(row -> result.put(row.key(), row)); return result; }
    }
    private record FurnitureDefinition(String name, Material material, Map<Material, Integer> materials) {}
    private record UpgradeRequirement(long price, long innerPower, long carpentryXp, int furnitureTypes, Map<Material, Integer> materials) {}
}
