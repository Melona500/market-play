package kr.hyuni.marketplay;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class ExplorationManager implements Listener {
    static final String WORLD = "mp_exploration";
    static final int Y = 64;
    private static final Material MAP_VERSION = Material.CHISELED_STONE_BRICKS;
    private static final Box SEA = new Box(0, 58, 8, 55, 76, 66);
    private static final Box DEEP = new Box(4, 58, 34, 51, 66, 64);
    private static final Box CASTLE = new Box(4, 63, -62, 54, 82, -20);
    private static final List<LocationKey> DEVICES = List.of(
            new LocationKey(8, 61, 48), new LocationKey(47, 61, 48),
            new LocationKey(8, 61, 60), new LocationKey(47, 61, 60));
    private static final List<Node> NODES = List.of(
            new Node("coral", "산호", new LocationKey(12, 61, 28), Material.TUBE_CORAL, Material.TUBE_CORAL, Skill.FORAGING, "old_net", "남작", false),
            new Node("shell", "조개", new LocationKey(26, 61, 38), Material.SUSPICIOUS_SAND, Material.NAUTILUS_SHELL, Skill.FORAGING, "old_net", "남작", true),
            new Node("pearl", "진주", new LocationKey(42, 61, 56), Material.SEA_LANTERN, Material.ENDER_PEARL, Skill.FORAGING, "old_net", "남작", true),
            new Node("ruby", "조이광산 루비", new LocationKey(-58, 65, 0), Material.REDSTONE_ORE, Material.RED_DYE, Skill.MINING, "old_pickaxe", "평민", false),
            new Node("sapphire", "반짝광산 사파이어", new LocationKey(-38, 65, 0), Material.LAPIS_ORE, Material.LAPIS_LAZULI, Skill.MINING, "old_pickaxe", "자작", false),
            new Node("emerald", "반짝광산 에메랄드", new LocationKey(-36, 65, 3), Material.EMERALD_ORE, Material.EMERALD, Skill.MINING, "old_pickaxe", "자작", false),
            new Node("crystal", "요정광산 수정", new LocationKey(-18, 65, 0), Material.AMETHYST_BLOCK, Material.AMETHYST_SHARD, Skill.MINING, "old_pickaxe", "백작", false));

    private final MarketPlayPlugin plugin;
    private final ProfileStore profiles;
    private final HousingManager housing;
    private final NamespacedKey itemId;
    private final NamespacedKey itemSchema;
    private final NamespacedKey quality;
    private final NamespacedKey royalToken;
    private final NamespacedKey entityRole;
    private final NamespacedKey duelOwner;
    private final NamespacedKey intentKey;
    private final Map<UUID, Long> seaDeadlines = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deepSeconds = new ConcurrentHashMap<>();
    private World world;
    private BossSession boss;
    private boolean bossStarting;

    ExplorationManager(MarketPlayPlugin plugin, ProfileStore profiles, HousingManager housing) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.housing = housing;
        itemId = new NamespacedKey(plugin, "item_id");
        itemSchema = new NamespacedKey(plugin, "item_schema");
        quality = new NamespacedKey(plugin, "quality");
        royalToken = new NamespacedKey(plugin, "royal_gift_token");
        entityRole = new NamespacedKey(plugin, "exploration_role");
        duelOwner = new NamespacedKey(plugin, "duel_owner");
        intentKey = new NamespacedKey(plugin, "exploration_intent");
    }

    void start() {
        boolean existed = Files.exists(plugin.getServer().getWorldContainer().toPath().resolve(WORLD).resolve("level.dat"));
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = Bukkit.createWorld(new WorldCreator(WORLD).type(WorldType.FLAT).generateStructures(false).generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}"));
        if (world == null) throw new IllegalStateException("탐험 월드를 만들 수 없습니다.");
        Block marker = world.getBlockAt(0, Y - 1, 0);
        if (marker.getType() != Material.LODESTONE) {
            if (!existed || isLegacyWorld()) build();
            else throw new IllegalStateException("기존 탐험 월드에 설치 표식이 없어 덮어쓰지 않습니다.");
        } else if (world.getBlockAt(1, Y - 1, 0).getType() != MAP_VERSION) build();
        protect();
        spawnNpcs();
        for (int x = -5; x <= 4; x++) for (int z = -4; z <= 4; z++) world.setChunkForceLoaded(x, z, true);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickDeepSea, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickBoss, 20L, 20L);
        profiles.activeEncounter().whenComplete((active, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) plugin.getLogger().severe("심해 조우 복구 실패: " + error.getMessage());
            else active.ifPresent(this::spawnEncounter);
        }));
    }

    void stop() {
        if (boss != null) finishBoss(false);
        if (world != null) for (int x = -5; x <= 4; x++) for (int z = -4; z <= 4; z++) world.setChunkForceLoaded(x, z, false);
    }

    boolean command(Player player, String[] args) {
        if (args[0].equalsIgnoreCase("explore")) return explore(player, args);
        if (args[0].equalsIgnoreCase("craft")) return craft(player, args);
        if (args[0].equalsIgnoreCase("royal")) return royal(player, args);
        if (args[0].equalsIgnoreCase("knight")) return knight(player, args);
        return false;
    }

    void teleportEntrance(Player player) { player.teleport(new Location(world, -58.5, 65, 8.5)); }

    private boolean explore(Player player, String[] args) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null) return message(player, "플레이어 데이터를 불러오는 중입니다.", false);
        if (args.length < 2) return message(player, "/mp explore sea|joy|glitter|fairy|castle|plaza", true);
        Location destination = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "sea" -> gate(profile, "남작", player, "바다는 남작부터 입장할 수 있습니다.") ? new Location(world, 27.5, 66, 11.5) : null;
            case "joy" -> new Location(world, -58.5, 65, 6.5);
            case "glitter" -> gate(profile, "자작", player, "반짝광산은 자작부터 입장할 수 있습니다.") ? new Location(world, -38.5, 65, 6.5) : null;
            case "fairy" -> gate(profile, "백작", player, "요정광산은 백작부터 입장할 수 있습니다.") ? new Location(world, -18.5, 65, 6.5) : null;
            case "castle" -> gate(profile, "자작", player, "왕성은 자작부터 입장할 수 있습니다.") ? new Location(world, 29.5, 65, -24.5) : null;
            case "plaza" -> plugin.lobbyLocation();
            default -> null;
        };
        if (destination == null) return true;
        player.teleport(destination);
        return true;
    }

    private boolean craft(Player player, String[] args) {
        if (args.length != 2) return message(player, "/mp craft ring|necklace|gift|decor", true);
        String type = args[1].toLowerCase(Locale.ROOT);
        Craft recipe = switch (type) {
            case "ring" -> new Craft("ruby", Material.GOLD_INGOT, Material.GOLD_NUGGET, "ruby_ring", "루비 반지");
            case "necklace" -> new Craft("sapphire", Material.STRING, Material.CHAIN, "sapphire_necklace", "사파이어 목걸이");
            case "gift" -> new Craft("emerald", Material.GOLD_INGOT, Material.PAPER, "royal_gift", "왕실 선물");
            case "decor" -> new Craft("crystal", Material.GLASS, Material.AMETHYST_BLOCK, "royal_decor", "왕실 수정 장식");
            default -> null;
        };
        if (recipe == null) return message(player, "제작: ring, necklace, gift, decor", false);
        if (player.getInventory().firstEmpty() < 0) return message(player, "인벤토리 공간이 없습니다.", false);
        ItemStack gem = findResource(player, recipe.gem());
        ItemStack extra = findMaterial(player, recipe.extra());
        if (gem == null || extra == null) return message(player, "보석과 부재료가 부족합니다.", false);
        ItemStack result = type.equals("decor") ? housing.furnitureItem("royal_decor", "왕실") : tagged(recipe.material(), recipe.id(), quality(gem));
        result.editMeta(meta -> {
            meta.displayName(Component.text(recipe.name(), NamedTextColor.GOLD));
            if (type.equals("gift")) meta.getPersistentDataContainer().set(royalToken, PersistentDataType.STRING, UUID.randomUUID().toString());
        });
        return beginIntent(player, "CRAFT", gem, extra, result, null, recipe.name() + " 제작 완료");
    }

    private boolean royal(Player player, String[] args) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !gate(profile, "자작", player, "왕실은 자작부터 이용할 수 있습니다.")) return true;
        if (args.length < 2) return message(player, "왕실 평판 " + profile.royalReputation() + " · 칭호 " + royalTitle(profile.royalReputation()) + " · /mp royal order|shop|buy", true);
        if (args[1].equalsIgnoreCase("shop")) return message(player, "왕실 상점: 평판 10 이상 /mp royal buy oxygen (1000원)", true);
        if (args[1].equalsIgnoreCase("buy") && args.length == 3 && args[2].equalsIgnoreCase("oxygen")) return buyOxygen(player, profile);
        if (!args[1].equalsIgnoreCase("order")) return message(player, "/mp royal order|shop|buy oxygen", false);
        ItemStack gift = findExact(player, "royal_gift");
        String token = gift == null ? null : gift.getPersistentDataContainer().get(royalToken, PersistentDataType.STRING);
        if (token == null) return message(player, "고유 왕실 선물을 손질해 제출하세요.", false);
        return beginIntent(player, "ROYAL", gift, null, null, token, "왕실 의뢰 완료");
    }

    private boolean buyOxygen(Player player, PlayerProfile profile) {
        if (profile.royalReputation() < 10 || !plugin.tryLock(player.getUniqueId())) return message(player, "왕실 평판 10이 필요합니다.", false);
        String request = UUID.randomUUID().toString();
        ItemStack item = tagged(Material.HEART_OF_THE_SEA, "oxygen_device", 5);
        item.editMeta(meta -> { meta.displayName(Component.text("왕실 산소 장치", NamedTextColor.AQUA)); meta.getPersistentDataContainer().set(plugin.grantKey(), PersistentDataType.STRING, request); });
        profiles.purchaseItem(profile, 1000, "royal:oxygen_device", item.serializeAsBytes(), request).whenComplete((balance, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.unlock(player.getUniqueId()); message(player, "돈이 부족하거나 구매에 실패했습니다.", false); return; }
            profile.setMoney(balance);
            plugin.deliverPendingGrants(player);
            message(player, "왕실 산소 장치 구매 · 잔액 " + balance + "원", true);
        }));
        return true;
    }

    void recover(Player player, Runnable done) {
        profiles.activeExplorationIntent(player.getUniqueId()).whenComplete((pending, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { player.kick(Component.text("탐험 제작 상태를 확인하지 못했습니다.")); return; }
            if (pending.isEmpty()) { done.run(); return; }
            ProfileStore.ExplorationIntent intent = pending.get();
            int marked = markedCount(player, intent.id());
            if (intent.state().equals("PREPARED") || marked > 0) {
                unmark(player, intent.id()); player.saveData();
                profiles.cancelExplorationIntent(intent.id()).whenComplete((ignored, cancelError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (cancelError != null) player.kick(Component.text("탐험 제작 취소 복구에 실패했습니다.")); else done.run();
                }));
                return;
            }
            PlayerProfile profile = plugin.profile(player.getUniqueId());
            if (profile == null) { player.kick(Component.text("탐험 데이터를 불러오지 못했습니다.")); return; }
            profiles.completeExplorationIntent(profile, intent.id()).whenComplete((result, completeError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (completeError != null) { player.kick(Component.text("탐험 제작 완료 복구에 실패했습니다.")); return; }
                done.run();
            }));
        }));
    }

    private boolean beginIntent(Player player, String kind, ItemStack inputA, ItemStack inputB, ItemStack output, String token, String success) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !plugin.tryLock(player.getUniqueId())) return message(player, "다른 작업이 진행 중입니다.", false);
        String id = UUID.randomUUID().toString(), grant = output == null ? null : UUID.randomUUID().toString();
        ItemStack refundA = one(inputA), refundB = inputB == null ? null : one(inputB);
        if (output != null) output.editPersistentDataContainer(data -> data.set(plugin.grantKey(), PersistentDataType.STRING, grant));
        ProfileStore.ExplorationIntent intent = new ProfileStore.ExplorationIntent(id, player.getUniqueId(), kind,
                refundA.serializeAsBytes(), refundB == null ? null : refundB.serializeAsBytes(), output == null ? null : output.serializeAsBytes(), token, grant);
        profiles.prepareExplorationIntent(intent).whenComplete((ignored, prepareError) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (prepareError != null || !player.isOnline() || !similar(inputA, refundA) || (inputB != null && !similar(inputB, refundB))) {
                profiles.cancelExplorationIntent(id); plugin.unlock(player.getUniqueId()); message(player, "제작 준비에 실패했습니다.", false); return;
            }
            mark(inputA, id); if (inputB != null) mark(inputB, id); player.saveData();
            profiles.markExplorationRemoving(id).whenComplete((removing, markError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (markError != null) { unmark(player, id); player.saveData(); profiles.cancelExplorationIntent(id); plugin.unlock(player.getUniqueId()); message(player, "제작 잠금에 실패했습니다.", false); return; }
                removeMarked(player, id); player.saveData();
                profiles.completeExplorationIntent(profile, id).whenComplete((result, completeError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (completeError != null) { plugin.unlock(player.getUniqueId()); message(player, "재접속하면 제작을 자동 복구합니다.", false); return; }
                    if (result.kind().equals("CRAFT")) plugin.deliverPendingGrants(player); else plugin.unlock(player.getUniqueId());
                    message(player, result.kind().equals("ROYAL") ? success + " · 평판 " + result.value() + " · " + royalTitle(result.value()) : success, true);
                }));
            }));
        }));
        return true;
    }

    private ItemStack one(ItemStack item) { ItemStack copy = item.clone(); copy.setAmount(1); return copy; }
    private boolean similar(ItemStack item, ItemStack expected) { return item != null && item.getAmount() > 0 && item.isSimilar(expected); }
    private void mark(ItemStack item, String id) { item.editPersistentDataContainer(data -> data.set(intentKey, PersistentDataType.STRING, id)); }
    private int markedCount(Player player, String id) {
        int count = 0; for (ItemStack item : player.getInventory().getContents()) if (item != null && id.equals(item.getPersistentDataContainer().get(intentKey, PersistentDataType.STRING))) count++;
        return count;
    }
    private void unmark(Player player, String id) { for (ItemStack item : player.getInventory().getContents()) if (item != null && id.equals(item.getPersistentDataContainer().get(intentKey, PersistentDataType.STRING))) item.editPersistentDataContainer(data -> data.remove(intentKey)); }
    private void removeMarked(Player player, String id) {
        for (int slot = 0; slot < player.getInventory().getContents().length; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !id.equals(item.getPersistentDataContainer().get(intentKey, PersistentDataType.STRING))) continue;
            if (item.getAmount() == 1) player.getInventory().setItem(slot, null); else { item.setAmount(item.getAmount() - 1); item.editPersistentDataContainer(data -> data.remove(intentKey)); }
        }
    }

    private boolean knight(Player player, String[] args) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !gate(profile, "자작", player, "기사 시험은 자작부터 응시할 수 있습니다.")) return true;
        if (args.length < 2) return message(player, "기사 상태 " + profile.knightState() + " · /mp knight start", true);
        if (!args[1].equalsIgnoreCase("start") || profile.knightState().equals("APPRENTICE")) return message(player, profile.knightState().equals("APPRENTICE") ? "이미 견습기사입니다." : "/mp knight start", false);
        if (!CASTLE.contains(player.getLocation())) return message(player, "왕성 기사단 훈련장에서 시작하세요.", false);
        if (profile.royalReputation() < 10) return message(player, "왕실 평판 10이 필요합니다.", false);
        profile.setKnightState("ARCHERY");
        plugin.saveProfile(profile);
        return message(player, "1차 시험: 서쪽 과녁을 활로 맞히세요.", true);
    }

    boolean onSeaFish(PlayerFishEvent event) {
        if (!WORLD.equals(event.getPlayer().getWorld().getName()) || !SEA.contains(event.getPlayer().getLocation())) return false;
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !plugin.atLeast(profile, "남작") || !(player.getVehicle() instanceof Boat) || !"old_rod".equals(tool(player, event.getHand()))) {
            event.setCancelled(true);
            message(player, "남작·배·전용 낡은 낚싯대가 필요합니다.", false);
            return true;
        }
        if (event.getState() == PlayerFishEvent.State.FISHING) { seaDeadlines.remove(player.getUniqueId()); return true; }
        if (event.getState() == PlayerFishEvent.State.BITE) { seaDeadlines.put(player.getUniqueId(), System.currentTimeMillis() + 1200); player.sendActionBar(Component.text("지금 감아 올리세요!", NamedTextColor.GOLD)); return true; }
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            Long deadline = seaDeadlines.remove(player.getUniqueId());
            if (deadline == null || System.currentTimeMillis() > deadline) { event.setCancelled(true); if (event.getCaught() != null) event.getCaught().remove(); return true; }
            if (event.getCaught() != null) event.getCaught().remove();
            event.setCancelled(true);
            int q = rollQuality();
            give(player, tagged(Material.COD, "deep_cod_q" + q, q));
            rewardLife(player, Skill.FISHING);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null || !WORLD.equals(event.getClickedBlock().getWorld().getName())) return;
        for (Node node : NODES) if (node.location().matches(event.getClickedBlock())) { event.setCancelled(true); harvest(event.getPlayer(), node); return; }
        for (int i = 0; i < DEVICES.size(); i++) if (DEVICES.get(i).matches(event.getClickedBlock())) { event.setCancelled(true); activateDevice(event.getPlayer(), i); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void onNpc(PlayerInteractEntityEvent event) {
        String role = event.getRightClicked().getPersistentDataContainer().get(entityRole, PersistentDataType.STRING);
        if (role == null) return;
        event.setCancelled(true);
        String help = switch (role) {
            case "steward" -> "/mp royal order · 왕실 선물 제출";
            case "shop" -> "/mp royal shop · 왕실 상점";
            case "alchemist" -> "왕실 산소 장치는 심해에서 수중 호흡을 돕습니다.";
            case "jeweler" -> "/mp craft ring|necklace|gift|decor";
            default -> "/mp knight start · 활쏘기 후 목각 병사 대련";
        };
        message(event.getPlayer(), help, true);
    }

    @EventHandler(ignoreCancelled = true) public void onProjectile(ProjectileHitEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player) || event.getHitBlock() == null || !new LocationKey(9, 68, -45).matches(event.getHitBlock())) return;
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !profile.knightState().equals("ARCHERY")) return;
        profile.setKnightState("DUEL");
        plugin.saveProfile(profile);
        Zombie soldier = world.spawn(new Location(world, 18.5, 65, -43.5), Zombie.class);
        soldier.customName(Component.text("목각 병사", NamedTextColor.GOLD)); soldier.setCustomNameVisible(true); soldier.setPersistent(true);
        soldier.getPersistentDataContainer().set(duelOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        soldier.getEquipment().setItemInMainHand(new ItemStack(Material.WOODEN_SWORD));
        soldier.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        message(player, "2차 시험: 목각 병사를 쓰러뜨리세요.", true);
    }

    @EventHandler(ignoreCancelled = true) public void onDamage(EntityDamageByEntityEvent event) {
        String owner = event.getEntity().getPersistentDataContainer().get(duelOwner, PersistentDataType.STRING);
        if (owner != null && (!(event.getDamager() instanceof Player player) || !player.getUniqueId().toString().equals(owner))) { event.setCancelled(true); return; }
        if (boss == null || !event.getEntity().getUniqueId().equals(boss.body().getUniqueId())) return;
        Player attacker = event.getDamager() instanceof Player player ? player : null;
        PlayerProfile profile = attacker == null ? null : plugin.profile(attacker.getUniqueId());
        if (profile == null || !plugin.atLeast(profile, "남작") || !DEEP.contains(attacker.getLocation()) || System.currentTimeMillis() >= boss.staggerUntil()) {
            event.setCancelled(true);
            if (attacker != null) attacker.sendActionBar(Component.text("남작 계급과 심해 장치 4개가 필요합니다.", NamedTextColor.RED));
        }
    }

    @EventHandler public void onDeath(EntityDeathEvent event) {
        String owner = event.getEntity().getPersistentDataContainer().get(duelOwner, PersistentDataType.STRING);
        if (owner != null) {
            Player player = Bukkit.getPlayer(UUID.fromString(owner));
            PlayerProfile profile = player == null ? null : plugin.profile(player.getUniqueId());
            if (profile != null && profile.knightState().equals("DUEL")) {
                profile.setKnightState("APPRENTICE"); profile.addRoyalReputation(15); plugin.saveProfile(profile);
                message(player, "기사 시험 합격 · 견습기사 · 왕실 평판 +15", true);
            }
        }
        if (boss != null && event.getEntity().getUniqueId().equals(boss.body().getUniqueId())) finishBoss(true);
    }

    private void harvest(Player player, Node node) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !plugin.atLeast(profile, node.rank())) { message(player, "이 광맥·해역은 " + node.rank() + " 계급이 필요합니다.", false); return; }
        if (!profile.hasTool(node.tool())) { message(player, "전용 생활도구함의 도구가 필요합니다.", false); return; }
        if (node.diving() && (!player.isInWater() || !DEEP.contains(player.getLocation()))) { message(player, "심해에서 잠수해 채집하세요.", false); return; }
        if (profile.vitality() < plugin.activityCost()) { message(player, "활력이 부족합니다.", false); return; }
        if (!plugin.tryLock(player.getUniqueId())) return;
        int q = rollQuality();
        ItemStack reward = tagged(node.reward(), node.id() + "_q" + q, q);
        reward.editMeta(meta -> meta.displayName(Component.text(qualityName(q) + " " + node.name(), NamedTextColor.GREEN)));
        String grant = UUID.randomUUID().toString();
        reward.editPersistentDataContainer(data -> data.set(plugin.grantKey(), PersistentDataType.STRING, grant));
        long now = System.currentTimeMillis();
        profiles.harvestNode(profile, node.id(), now, now + plugin.getConfig().getLong("exploration.node-cooldown-millis", 15000),
                plugin.activityCost(), node.skill(), 2, reward.serializeAsBytes(), grant).whenComplete((harvest, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.unlock(player.getUniqueId()); player.sendActionBar(Component.text("전용 광맥이 재생 중이거나 채집에 실패했습니다.", NamedTextColor.YELLOW)); return; }
            plugin.deliverPendingGrants(player);
            player.sendActionBar(Component.text(qualityName(q) + " " + node.name() + " 획득", NamedTextColor.GREEN));
        }));
    }

    private void tickDeepSea() {
        for (Player player : world.getPlayers()) {
            if (!DEEP.contains(player.getLocation()) || !player.isInWater()) { deepSeconds.remove(player.getUniqueId()); continue; }
            PlayerProfile profile = plugin.profile(player.getUniqueId());
            if (profile == null || !plugin.atLeast(profile, "남작")) continue;
            if (findExact(player, "oxygen_device") != null) player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 40, 0, true, false));
            int seconds = deepSeconds.merge(player.getUniqueId(), 1, Integer::sum);
            int air = Math.max(0, player.getRemainingAir() * 100 / Math.max(1, player.getMaximumAir()));
            player.sendActionBar(Component.text("산소 " + air + "% · 심해의 기척 " + profile.deepOmen() + "%", NamedTextColor.AQUA));
            if (seconds % 5 != 0 || boss != null || bossStarting) continue;
            profile.setDeepOmen(profile.deepOmen() + 10);
            plugin.saveProfile(profile);
            if (profile.deepOmen() >= 100) spawnBoss(profile);
        }
    }

    private void spawnBoss(PlayerProfile profile) {
        bossStarting = true;
        profiles.startEncounter(profile).whenComplete((encounter, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            bossStarting = false;
            if (error != null) { plugin.getLogger().severe("대왕문어 조우 생성 실패: " + error.getMessage()); return; }
            profile.setDeepOmen(0);
            if (boss == null) spawnEncounter(encounter);
        }));
    }

    private void spawnEncounter(ProfileStore.Encounter encounter) {
        Squid body = world.spawn(new Location(world, 27.5, 61, 56.5), Squid.class);
        body.customName(Component.text("대왕문어", NamedTextColor.DARK_PURPLE)); body.setCustomNameVisible(true); body.setPersistent(true);
        body.getPersistentDataContainer().set(entityRole, PersistentDataType.STRING, "octopus");
        Objects.requireNonNull(body.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(200);
        Objects.requireNonNull(body.getAttribute(Attribute.SCALE)).setBaseValue(4);
        body.setHealth(Math.max(1, Math.min(200, encounter.hp())));
        List<Guardian> tentacles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3;
            Guardian tentacle = world.spawn(new Location(world, 27.5 + Math.cos(angle) * 7, 61, 56.5 + Math.sin(angle) * 7), Guardian.class);
            tentacle.customName(Component.text("문어 촉수", NamedTextColor.LIGHT_PURPLE)); tentacle.setPersistent(true);
            tentacle.getPersistentDataContainer().set(entityRole, PersistentDataType.STRING, "tentacle");
            tentacles.add(tentacle);
        }
        BossBar bar = Bukkit.createBossBar("대왕문어 · 장치 0/4", BarColor.PURPLE, BarStyle.SEGMENTED_10);
        world.getPlayers().stream().filter(player -> DEEP.contains(player.getLocation())).forEach(bar::addPlayer);
        boss = new BossSession(encounter.id(), body, tentacles, bar, new HashSet<>(), 0, 0L);
        Bukkit.broadcast(Component.text("심해의 기척이 대왕문어를 깨웠습니다!", NamedTextColor.DARK_PURPLE));
    }

    private void activateDevice(Player player, int device) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !plugin.atLeast(profile, "남작") || boss == null || !DEEP.contains(player.getLocation())) { message(player, "남작의 대왕문어 전투 중에만 작동합니다.", false); return; }
        boss.devices().add(device);
        boss.bar().setTitle("대왕문어 · 장치 " + boss.devices().size() + "/4");
        if (boss.devices().size() < 4) return;
        boss.devices().clear(); boss.setStaggerUntil(System.currentTimeMillis() + 10000); boss.body().setGlowing(true);
        Bukkit.broadcast(Component.text("대왕문어 그로기! 10초 동안 공격하세요.", NamedTextColor.GOLD));
    }

    private void tickBoss() {
        if (boss == null || !boss.body().isValid() || boss.body().isDead()) return;
        boss.setTicks(boss.ticks() + 1);
        double max = Objects.requireNonNull(boss.body().getAttribute(Attribute.MAX_HEALTH)).getValue();
        boss.bar().setProgress(Math.max(0, Math.min(1, boss.body().getHealth() / max)));
        if (boss.ticks() % 5 == 0) profiles.saveEncounterHp(boss.id(), boss.body().getHealth());
        if (System.currentTimeMillis() >= boss.staggerUntil()) boss.body().setGlowing(false);
        if (boss.ticks() % 6 == 0) for (Player player : world.getPlayers()) if (DEEP.contains(player.getLocation())) {
            Vector current = boss.body().getLocation().toVector().subtract(player.getLocation().toVector()).normalize().multiply(.55);
            player.setVelocity(player.getVelocity().add(current));
            player.sendActionBar(Component.text("물살에 끌려갑니다. 촉수를 피하세요!", NamedTextColor.RED));
        }
        if (boss.ticks() % 8 == 0) {
            world.spawnParticle(Particle.BUBBLE_COLUMN_UP, boss.body().getLocation(), 160, 6, 3, 6, .1);
            for (Player player : world.getPlayers()) if (player.getLocation().distanceSquared(boss.body().getLocation()) < 49) player.damage(3, boss.body());
        }
    }

    private void finishBoss(boolean victory) {
        BossSession ended = boss;
        boss = null;
        if (ended == null) return;
        ended.bar().removeAll();
        ended.tentacles().forEach(Entity::remove);
        if (ended.body().isValid()) ended.body().remove();
        if (!victory) return;
        List<ProfileStore.BossReward> rewards = new ArrayList<>();
        for (Player player : world.getPlayers()) if (DEEP.contains(player.getLocation()) && plugin.profile(player.getUniqueId()) != null && plugin.atLeast(plugin.profile(player.getUniqueId()), "남작")) {
            String grant = UUID.randomUUID().toString();
            ItemStack pearl = tagged(Material.ENDER_PEARL, "pearl_q5", 5);
            pearl.editMeta(meta -> meta.displayName(Component.text("신비 대왕문어 진주", NamedTextColor.LIGHT_PURPLE)));
            pearl.editPersistentDataContainer(data -> data.set(plugin.grantKey(), PersistentDataType.STRING, grant));
            rewards.add(new ProfileStore.BossReward(player.getUniqueId(), grant, pearl.serializeAsBytes()));
        }
        profiles.defeatEncounter(ended.id(), rewards).whenComplete((reputations, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.getLogger().severe("대왕문어 보상 저장 실패: " + error.getMessage()); return; }
            reputations.forEach((id, reputation) -> {
                Player player = Bukkit.getPlayer(id); PlayerProfile profile = plugin.profile(id);
                if (profile != null) profile.setRoyalReputation(reputation);
                if (player != null) { plugin.lock(id); plugin.deliverPendingGrants(player); message(player, "대왕문어 토벌 · 왕실 평판 +20", true); }
            });
        }));
    }

    private void build() {
        world.getEntitiesByClass(TextDisplay.class).forEach(Entity::remove);
        fill(-65, 63, -68, 60, 82, 70, Material.AIR);
        fill(-65, 63, -68, 60, 63, 70, Material.GRASS_BLOCK);
        for (int x = 0; x <= 55; x++) for (int z = 8; z <= 66; z++) {
            world.getBlockAt(x, 60, z).setType(z < 18 ? Material.SAND : Material.PRISMARINE);
            for (int y = 61; y <= 64; y++) world.getBlockAt(x, y, z).setType(z < 18 ? (y == 64 ? Material.SAND : Material.SANDSTONE) : Material.WATER);
        }
        buildBoundary();
        path(-64, -8, 8, true);
        path(-8, 29, 7, true);
        path(-18, 7, 29, false);
        tree(-55, -22); tree(-39, -18); tree(-21, -24); tree(-6, -8);
        mound(-50, -43, 5); mound(-23, -47, 4); mound(-3, -55, 5);
        NODES.forEach(node -> node.location().block(world).setType(node.block()));
        DEVICES.forEach(device -> device.block(world).setType(Material.RESPAWN_ANCHOR));
        buildMine(-60, Material.REDSTONE_BLOCK, "조이광산 · 평민");
        buildMine(-40, Material.LAPIS_BLOCK, "반짝광산 · 자작");
        buildMine(-20, Material.AMETHYST_BLOCK, "요정광산 · 백작");
        buildCastle();
        lighthouse();
        display(new Location(world, 27.5, 68, 11.5), "해변과 바다 · 남작\n배낚시 / 그물 / 잠수 / 심해", NamedTextColor.AQUA);
        display(new Location(world, 29.5, 69, -24.5), "왕성 · 자작\n왕실 / 기사단 / 상점 / 의뢰", NamedTextColor.GOLD);
        world.getBlockAt(0, Y - 1, 0).setType(Material.LODESTONE);
        world.getBlockAt(1, Y - 1, 0).setType(MAP_VERSION);
        world.setSpawnLocation(new Location(world, -58.5, 65, 8.5));
    }

    private boolean isLegacyWorld() {
        return world.getBlockAt(8, 61, 48).getType() == Material.RESPAWN_ANCHOR
                && world.getBlockAt(9, 68, -45).getType() == Material.TARGET
                && world.getBlockAt(-60, 64, 6).getType() == Material.REDSTONE_BLOCK;
    }

    private void buildMine(int x, Material accent, String name) {
        fill(x - 7, 64, -7, x + 7, 64, 7, Material.DEEPSLATE_TILES);
        fill(x - 7, 65, -7, x + 7, 70, -7, Material.DEEPSLATE_BRICKS);
        fill(x - 7, 65, 7, x + 7, 70, 7, Material.DEEPSLATE_BRICKS);
        fill(x - 7, 65, -7, x - 7, 70, 7, Material.DEEPSLATE_BRICKS);
        fill(x + 7, 65, -7, x + 7, 70, 7, Material.DEEPSLATE_BRICKS);
        fill(x - 7, 71, -7, x + 7, 71, 7, Material.DEEPSLATE_TILES);
        fill(x - 2, 65, 7, x + 2, 68, 7, Material.AIR);
        for (int z = -4; z <= 4; z += 4) {
            world.getBlockAt(x - 5, 65, z).setType(Material.OAK_LOG);
            world.getBlockAt(x + 5, 65, z).setType(Material.OAK_LOG);
            world.getBlockAt(x, 70, z).setType(Material.LANTERN);
        }
        world.getBlockAt(x, 64, 6).setType(accent);
        display(new Location(world, x + .5, 68, 6.5), name, NamedTextColor.GRAY);
    }

    private void buildCastle() {
        fill(4, 64, -62, 54, 64, -20, Material.STONE_BRICKS);
        fill(4, 65, -62, 54, 72, -62, Material.STONE_BRICKS);
        fill(4, 65, -20, 54, 72, -20, Material.STONE_BRICKS);
        fill(4, 65, -62, 4, 72, -20, Material.STONE_BRICKS);
        fill(54, 65, -62, 54, 72, -20, Material.STONE_BRICKS);
        fill(4, 73, -62, 54, 73, -20, Material.STONE_BRICK_SLAB);
        for (int x : List.of(4, 49)) for (int z : List.of(-62, -25)) {
            fill(x, 65, z, x + 5, 78, z + 5, Material.STONE_BRICKS);
            fill(x + 1, 66, z + 1, x + 4, 76, z + 4, Material.AIR);
            fill(x, 79, z, x + 5, 79, z + 5, Material.POLISHED_ANDESITE);
        }
        fill(26, 65, -20, 32, 70, -20, Material.AIR);
        fill(18, 65, -58, 18, 70, -24, Material.POLISHED_ANDESITE);
        fill(18, 65, -46, 18, 68, -40, Material.AIR);
        fill(19, 65, -43, 50, 70, -43, Material.POLISHED_ANDESITE);
        fill(27, 65, -43, 31, 68, -43, Material.AIR);
        for (int z = -58; z <= -24; z++) world.getBlockAt(29, 64, z).setType(Material.RED_CARPET);
        world.getBlockAt(22, 65, -33).setType(Material.LECTERN);
        world.getBlockAt(36, 65, -33).setType(Material.BARREL);
        world.getBlockAt(22, 65, -52).setType(Material.BREWING_STAND);
        world.getBlockAt(36, 65, -52).setType(Material.SMITHING_TABLE);
        world.getBlockAt(9, 68, -45).setType(Material.TARGET);
        for (int z = -55; z <= -31; z += 4) world.getBlockAt(12, 65, z).setType(Material.OAK_FENCE);
    }

    private void buildBoundary() {
        for (int x = -65; x <= 60; x++) for (int y = 64; y <= 66 + Math.floorMod(x, 3); y++) world.getBlockAt(x, y, -68).setType(y == 64 ? Material.STONE : Material.ANDESITE);
        for (int z = -67; z <= 7; z++) for (int y = 64; y <= 66 + Math.floorMod(z, 2); y++) {
            world.getBlockAt(-65, y, z).setType(Material.MOSSY_COBBLESTONE);
            world.getBlockAt(60, y, z).setType(Material.STONE);
        }
        for (int z = 12; z <= 70; z++) for (int y = 64; y <= 69 + Math.floorMod(z, 3); y++) world.getBlockAt(-5, y, z).setType(y < 67 ? Material.STONE : Material.DIRT);
        fill(0, 60, 67, 55, 64, 70, Material.PRISMARINE_BRICKS);
    }

    private void path(int from, int to, int fixed, boolean xAxis) {
        for (int value = from; value <= to; value++) for (int side = -1; side <= 1; side++)
            world.getBlockAt(xAxis ? value : fixed + side, 63, xAxis ? fixed + side : value).setType(Material.DIRT_PATH);
    }

    private void tree(int x, int z) {
        fill(x, 64, z, x, 69, z, Material.OAK_LOG);
        fill(x - 2, 68, z - 2, x + 2, 70, z + 2, Material.OAK_LEAVES);
        world.getBlockAt(x, 71, z).setType(Material.OAK_LEAVES);
    }

    private void mound(int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int height = Math.max(0, radius - (Math.abs(dx) + Math.abs(dz)) / 2);
            for (int y = 64; y <= 64 + height; y++) world.getBlockAt(x + dx, y, z + dz).setType(y == 64 + height ? Material.GRASS_BLOCK : Material.STONE);
        }
    }

    private void lighthouse() {
        for (int y = 64; y <= 76; y++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) world.getBlockAt(57 + dx, y, 12 + dz).setType(y % 4 < 2 ? Material.WHITE_CONCRETE : Material.RED_CONCRETE);
        fill(55, 77, 10, 59, 77, 14, Material.GLASS);
        world.getBlockAt(57, 78, 12).setType(Material.SEA_LANTERN);
        fill(55, 65, 10, 55, 67, 12, Material.AIR);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) world.getBlockAt(x, y, z).setType(material, false);
    }

    private void protect() {
        RegionManager regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regions == null) throw new IllegalStateException("탐험 WorldGuard region manager를 열 수 없습니다.");
        GlobalProtectedRegion global = regions.getRegion("__global__") instanceof GlobalProtectedRegion found ? found : new GlobalProtectedRegion("__global__");
        global.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY); global.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        global.setFlag(Flags.USE, StateFlag.State.ALLOW); global.setFlag(Flags.INTERACT, StateFlag.State.ALLOW); global.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        for (String name : List.of("sit", "playersit", "pose", "crawl")) {
            Object flag = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (flag instanceof StateFlag state) global.setFlag(state, StateFlag.State.DENY);
        }
        regions.addRegion(global);
        try { regions.save(); } catch (Exception error) { throw new IllegalStateException("탐험 지역 보호 저장 실패", error); }
    }

    private void spawnNpcs() {
        if (!CitizensAPI.hasImplementation()) throw new IllegalStateException("Citizens가 없어 왕성 NPC를 만들 수 없습니다.");
        List<NPC> previous = new ArrayList<>();
        CitizensAPI.getNPCRegistry().forEach(npc -> { if (npc.data().has("marketplay_role")) previous.add(npc); });
        previous.forEach(NPC::destroy);
        world.getEntities().stream().filter(entity -> entity.getPersistentDataContainer().has(entityRole, PersistentDataType.STRING)).forEach(Entity::remove);
        npc("steward", "왕실 시종 · 의뢰", 22, -35);
        npc("shop", "왕실 상점", 36, -35);
        npc("alchemist", "왕실 연금술사", 22, -50);
        npc("jeweler", "왕실 보석세공사", 36, -50);
        npc("knight", "기사단 교관", 14, -43);
        CitizensAPI.getNPCRegistry().saveToStore();
    }

    private void npc(String role, String name, double x, double z) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
        npc.data().setPersistent("marketplay_role", role);
        npc.setProtected(true);
        npc.getOrAddTrait(LookClose.class).lookClose(true);
        Location target = new Location(world, x + .5, 65, z + .5);
        target.getChunk().load();
        if (!npc.spawn(target)) throw new IllegalStateException(name + " Citizens NPC 생성 실패");
        npc.getEntity().setPersistent(false);
        npc.getEntity().getPersistentDataContainer().set(entityRole, PersistentDataType.STRING, role);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (!event.hasChangedBlock() || to == null || !WORLD.equals(to.getWorld().getName())) return;
        if (!ProfileStore.insideExplorationMap(to.getBlockX(), to.getBlockZ())) event.setCancelled(true);
    }

    private void display(Location location, String text, NamedTextColor color) {
        world.spawn(location, TextDisplay.class, display -> { display.text(Component.text(text, color)); display.setBillboard(Display.Billboard.VERTICAL); display.setShadowed(true); });
    }

    private void rewardLife(Player player, Skill skill) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !profile.spendVitality(plugin.activityCost())) return;
        profile.addExperience(skill, 2); profile.addInnerPower(1); plugin.saveProfile(profile);
    }

    private ItemStack tagged(Material material, String id, int q) {
        ItemStack item = new ItemStack(material);
        item.editPersistentDataContainer(data -> { data.set(itemId, PersistentDataType.STRING, id); data.set(itemSchema, PersistentDataType.INTEGER, 1); data.set(quality, PersistentDataType.INTEGER, q); });
        return item;
    }

    private ItemStack findResource(Player player, String prefix) {
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null) {
            String id = item.getPersistentDataContainer().get(itemId, PersistentDataType.STRING);
            if (id != null && id.startsWith(prefix + "_q")) return item;
        }
        return null;
    }

    private ItemStack findMaterial(Player player, Material material) {
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && item.getType() == material && !item.hasItemMeta()) return item;
        return null;
    }

    private ItemStack findExact(Player player, String id) {
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && id.equals(item.getPersistentDataContainer().get(itemId, PersistentDataType.STRING))) return item;
        return null;
    }

    private void takeOne(Player player, ItemStack item) { if (item.getAmount() == 1) player.getInventory().removeItem(item); else item.setAmount(item.getAmount() - 1); }
    private int quality(ItemStack item) { return item.getPersistentDataContainer().getOrDefault(quality, PersistentDataType.INTEGER, 1); }
    private int rollQuality() { int roll = ThreadLocalRandom.current().nextInt(100); return roll < 40 ? 1 : roll < 70 ? 2 : roll < 88 ? 3 : roll < 97 ? 4 : 5; }
    private String qualityName(int q) { return List.of("", "낮음", "보통", "풍부", "희귀", "신비").get(Math.max(1, Math.min(5, q))); }
    private String tool(Player player, EquipmentSlot hand) { ItemStack item = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand(); return item.getPersistentDataContainer().get(new NamespacedKey(plugin, "tool_id"), PersistentDataType.STRING); }
    private boolean give(Player player, ItemStack item) { if (!player.getInventory().addItem(item).isEmpty()) { message(player, "인벤토리 공간이 없습니다.", false); return false; } player.saveData(); return true; }
    private boolean gate(PlayerProfile profile, String rank, Player player, String denied) { if (plugin.atLeast(profile, rank)) return true; message(player, denied, false); return false; }
    private String royalTitle(int reputation) { return reputation >= 50 ? "왕실 고문" : reputation >= 25 ? "왕실 장인" : reputation >= 10 ? "왕실 협력자" : "방문객"; }
    private boolean message(Player player, String text, boolean success) { player.sendMessage(Component.text(text, success ? NamedTextColor.GREEN : NamedTextColor.RED)); return true; }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(Location location) { return location.getWorld() != null && WORLD.equals(location.getWorld().getName()) && location.getBlockX() >= minX && location.getBlockX() <= maxX && location.getBlockY() >= minY && location.getBlockY() <= maxY && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ; }
    }
    private record LocationKey(int x, int y, int z) {
        boolean matches(Block block) { return WORLD.equals(block.getWorld().getName()) && block.getX() == x && block.getY() == y && block.getZ() == z; }
        Block block(World world) { return world.getBlockAt(x, y, z); }
    }
    private record Node(String id, String name, LocationKey location, Material block, Material reward, Skill skill, String tool, String rank, boolean diving) {}
    private record Craft(String gem, Material extra, Material material, String id, String name) {}
    private static final class BossSession {
        private final String id; private final Squid body; private final List<Guardian> tentacles; private final BossBar bar; private final Set<Integer> devices; private int ticks; private long staggerUntil;
        BossSession(String id, Squid body, List<Guardian> tentacles, BossBar bar, Set<Integer> devices, int ticks, long staggerUntil) { this.id = id; this.body = body; this.tentacles = tentacles; this.bar = bar; this.devices = devices; this.ticks = ticks; this.staggerUntil = staggerUntil; }
        String id() { return id; } Squid body() { return body; } List<Guardian> tentacles() { return tentacles; } BossBar bar() { return bar; } Set<Integer> devices() { return devices; } int ticks() { return ticks; } void setTicks(int value) { ticks = value; } long staggerUntil() { return staggerUntil; } void setStaggerUntil(long value) { staggerUntil = value; }
    }
}
