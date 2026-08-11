package kr.hyuni.marketplay;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class EndgameManager implements Listener {
    static final String WORLD = "mp_endgame";
    private static final int Y = 64, SLOT_X = 512, SLOT_GAP = 96, SLOT_RADIUS = 30;
    private final MarketPlayPlugin plugin;
    private final ProfileStore profiles;
    private final NamespacedKey sessionKey, roleKey, intentKey, maskKey, sprayerKey, wingsKey;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRuns = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> skyCheckpoints = new ConcurrentHashMap<>();
    private final Map<UUID, String> warriorPaths = new ConcurrentHashMap<>();
    private final Map<UUID, ProfileStore.Dragon> dragons = new ConcurrentHashMap<>();
    private World world;

    EndgameManager(MarketPlayPlugin plugin, ProfileStore profiles) {
        this.plugin = plugin; this.profiles = profiles;
        sessionKey = new NamespacedKey(plugin, "endgame_session"); roleKey = new NamespacedKey(plugin, "endgame_role");
        intentKey = new NamespacedKey(plugin, "endgame_intent"); maskKey = new NamespacedKey(plugin, "endgame_gas_mask");
        sprayerKey = new NamespacedKey(plugin, "endgame_sprayer"); wingsKey = new NamespacedKey(plugin, "heaven_wings");
    }

    void start() {
        boolean existed = Files.exists(plugin.getServer().getWorldContainer().toPath().resolve(WORLD).resolve("level.dat"));
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = Bukkit.createWorld(new WorldCreator(WORLD).type(WorldType.FLAT).generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}"));
        if (world == null) throw new IllegalStateException("후반 콘텐츠 월드를 만들 수 없습니다.");
        Block marker = world.getBlockAt(0, Y - 1, 0);
        if (marker.getType() != Material.LODESTONE) {
            if (existed) throw new IllegalStateException("기존 후반 콘텐츠 월드에 설치 표식이 없어 덮어쓰지 않습니다.");
            buildPersistent(); marker.setType(Material.LODESTONE);
        }
        protect(); Bukkit.getPluginManager().registerEvents(this, plugin);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) world.setChunkForceLoaded(x, z, true);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        profiles.activeEndgameSessions().whenComplete((active, error) -> main(() -> {
            if (error != null) { plugin.getLogger().severe("후반 콘텐츠 복구 실패: " + error.getMessage()); return; }
            active.forEach(session -> profiles.endgameMembers(session.id()).whenComplete((members, memberError) -> main(() -> {
                if (memberError != null) plugin.getLogger().severe("후반 콘텐츠 멤버 복구 실패: " + memberError.getMessage());
                else activate(session, members);
            })));
        }));
    }

    void stop() {
        runs.values().forEach(this::despawn);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) world.setChunkForceLoaded(x, z, false);
    }

    boolean command(Player player, String[] args) {
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "endgame" -> gear(player, args);
            case "dungeon" -> dungeon(player, args, false);
            case "tower" -> dungeon(player, args, true);
            case "dragon" -> dragon(player, args);
            case "deeds" -> deeds(player, args);
            case "heaven" -> heaven(player, args);
            case "warrior" -> warrior(player, args);
            default -> false;
        };
    }

    void recover(Player player, Runnable done) {
        profiles.pendingEndgameIntent(player.getUniqueId()).whenComplete((pending, error) -> main(() -> {
            if (error != null) { player.kick(Component.text("후반 콘텐츠 거래 상태를 확인하지 못했습니다.")); return; }
            if (pending.isEmpty()) { loadIdentity(player, done); return; }
            ProfileStore.EndgameIntent intent = pending.get();
            ItemStack marked = findMarked(player, intent.id());
            if (intent.state().equals("PREPARED") || marked != null) {
                if (marked != null) marked.editPersistentDataContainer(data -> data.remove(intentKey));
                player.saveData(); profiles.cancelEndgameIntent(intent.id()).whenComplete((v, cancelError) -> main(() -> {
                    if (cancelError != null) player.kick(Component.text("후반 콘텐츠 거래 취소 복구에 실패했습니다.")); else loadIdentity(player, done);
                }));
            } else profiles.completeEndgameIntent(intent.id()).whenComplete((v, completeError) -> main(() -> {
                if (completeError != null) player.kick(Component.text("후반 콘텐츠 거래 완료 복구에 실패했습니다.")); else { if (v.dragon() != null) dragons.put(player.getUniqueId(), v.dragon()); loadIdentity(player, done); }
            }));
        }));
    }

    private void loadIdentity(Player player, Runnable done) {
        profiles.warriorPath(player.getUniqueId()).thenCombine(profiles.dragon(player.getUniqueId()), (path, dragon) -> {
            path.ifPresent(value -> warriorPaths.put(player.getUniqueId(), value));
            dragon.ifPresent(value -> dragons.put(player.getUniqueId(), value));
            return null;
        }).whenComplete((v, error) -> main(() -> { if (error != null) player.kick(Component.text("후반 콘텐츠 정체성을 불러오지 못했습니다.")); else done.run(); }));
    }

    private boolean gear(Player player, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("gear")) return msg(player, "/mp endgame gear mask|sprayer", true);
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null || !plugin.tryLock(player.getUniqueId())) return msg(player, "다른 작업이 진행 중입니다.", false);
        String kind = args[2].toLowerCase(Locale.ROOT); ItemStack item; long price;
        if (kind.equals("mask")) { item = new ItemStack(Material.TURTLE_HELMET); item.editMeta(meta -> { meta.displayName(Component.text("쓰레기장 방독면", NamedTextColor.GREEN)); meta.getPersistentDataContainer().set(maskKey, PersistentDataType.BYTE, (byte) 1); }); price = 700; }
        else if (kind.equals("sprayer")) { item = new ItemStack(Material.BLAZE_ROD); item.editMeta(meta -> { meta.displayName(Component.text("해충 살충기", NamedTextColor.AQUA)); meta.getPersistentDataContainer().set(sprayerKey, PersistentDataType.BYTE, (byte) 1); }); price = 900; }
        else { plugin.unlock(player.getUniqueId()); return msg(player, "mask 또는 sprayer만 구매할 수 있습니다.", false); }
        String grant = UUID.randomUUID().toString(); item.editPersistentDataContainer(data -> data.set(plugin.grantKey(), PersistentDataType.STRING, grant));
        profiles.purchaseItem(profile, price, "endgame:" + kind, item.serializeAsBytes(), grant).whenComplete((balance, error) -> main(() -> {
            if (error != null) { plugin.unlock(player.getUniqueId()); msg(player, "돈이 부족하거나 구매에 실패했습니다.", false); }
            else { profile.setMoney(balance); plugin.deliverPendingGrants(player); msg(player, "구매 완료 · 잔액 " + balance + "원", true); }
        })); return true;
    }

    private boolean dungeon(Player player, String[] args, boolean tower) {
        String base = tower ? "tower" : "dungeon";
        if (args.length < 2) return msg(player, tower ? "/mp tower start solo|guild | enter | status | records" : "/mp dungeon start trash|pirate|anubis solo|guild | enter | status | abandon", true);
        if (args[1].equalsIgnoreCase("enter")) { Run run = run(player); if (run == null) return msg(player, "진행 중인 인스턴스가 없습니다.", false); player.teleport(spawn(run.session)); return true; }
        if (args[1].equalsIgnoreCase("status")) { Run run = run(player); return msg(player, run == null ? "진행 중인 인스턴스 없음" : run.session.content() + " · " + run.session.stage() + " · " + run.session.progress(), run != null); }
        if (args[1].equalsIgnoreCase("records") && tower) { profiles.towerRecords(week()).whenComplete((records, error) -> main(() -> msg(player, error == null ? recordsText(records) : "기록 조회 실패", error == null))); return true; }
        if (args[1].equalsIgnoreCase("abandon")) { Run run = run(player); if (run == null || !run.session.owner().equals(player.getUniqueId())) return msg(player, "인스턴스 소유자만 포기할 수 있습니다.", false); profiles.abandonEndgame(player.getUniqueId(), run.session.id()).whenComplete((v, e) -> main(() -> { if (e == null) close(run, true); msg(player, e == null ? "인스턴스를 포기했습니다." : "포기 실패", e == null); })); return true; }
        int modeIndex = tower ? 2 : 3;
        if (!args[1].equalsIgnoreCase("start") || args.length <= modeIndex) return msg(player, "/mp " + base + " start " + (tower ? "solo|guild" : "trash|pirate|anubis solo|guild"), false);
        String content = tower ? "TOWER" : args[2].toUpperCase(Locale.ROOT), scope = args[modeIndex].toUpperCase(Locale.ROOT);
        if (!Set.of("TRASH", "PIRATE", "ANUBIS", "TOWER").contains(content) || !Set.of("SOLO", "GUILD").contains(scope)) return msg(player, "콘텐츠 또는 모드가 올바르지 않습니다.", false);
        if (run(player) != null) return msg(player, "이미 진행 중인 인스턴스가 있습니다.", false);
        if (scope.equals("SOLO")) startSession(player, scope, player.getUniqueId().toString(), content, List.of(player.getUniqueId()));
        else profiles.guildParty(player.getUniqueId()).whenComplete((party, error) -> main(() -> {
            if (error != null) { msg(player, "상인 길드가 필요합니다.", false); return; }
            List<UUID> online = party.members().stream().filter(id -> Bukkit.getPlayer(id) != null).limit(8).toList();
            if (!online.contains(player.getUniqueId())) { msg(player, "파티 구성에 실패했습니다.", false); return; }
            startSession(player, scope, party.groupKey(), content, online);
        }));
        return true;
    }

    private void startSession(Player player, String scope, String group, String content, List<UUID> members) {
        profiles.startEndgameSession(player.getUniqueId(), scope, group, content, members).whenComplete((session, error) -> main(() -> {
            if (error != null) { msg(player, root(error).contains("No free") ? "인스턴스 슬롯이 가득 찼습니다." : "인스턴스 시작 실패: " + root(error), false); return; }
            activate(session, Set.copyOf(members)); members.forEach(id -> Optional.ofNullable(Bukkit.getPlayer(id)).ifPresent(member -> member.teleport(spawn(session))));
            msg(player, content + " 인스턴스 시작", true);
        }));
    }

    private boolean dragon(Player player, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) { profiles.dragon(player.getUniqueId()).whenComplete((dragon, error) -> main(() -> msg(player, error == null ? dragon.map(d -> d.stage() + " · " + d.trait() + " · 먹이 " + d.feedTotal() + "/12").orElse("용 없음") : "용 조회 실패", error == null))); return true; }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (args[1].equalsIgnoreCase("hatch")) {
            if (hand.getType() != Material.DRAGON_EGG) return msg(player, "용의 알을 주 손에 드세요.", false);
            return consume(player, "HATCH", "DRAGON_EGG", hand, 1, "용이 부화했습니다.");
        }
        if (!args[1].equalsIgnoreCase("feed")) return msg(player, "/mp dragon hatch|feed|status", true);
        String category = food(hand.getType()); if (category == null) return msg(player, "생선·채소·과일·고기·광물·요리 먹이를 드세요.", false);
        return consume(player, "FEED", category, hand, 1, "용에게 먹이를 줬습니다.");
    }

    private boolean deeds(Player player, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) { profiles.goodDeeds(player.getUniqueId()).whenComplete((d, e) -> main(() -> msg(player, e == null ? deedsText(d) : "선행 조회 실패", e == null))); return true; }
        if (args[1].equalsIgnoreCase("delivery")) { ItemStack hand = player.getInventory().getItemInMainHand(); if (!Set.of(Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.COBBLESTONE).contains(hand.getType())) return msg(player, "원목 또는 조약돌 8개를 주 손에 드세요.", false); return consume(player, "DELIVERY", hand.getType().name(), hand, 8, "공동 납품 완료"); }
        if (args[1].equalsIgnoreCase("help")) { ItemStack hand = player.getInventory().getItemInMainHand(); if (hand.getType() != Material.BREAD) return msg(player, "빵 3개를 주 손에 드세요.", false); return consume(player, "HELP", "BREAD", hand, 3, "NPC 도움 완료"); }
        if (args[1].equalsIgnoreCase("donate") && args.length == 3) { long amount; try { amount = Long.parseLong(args[2]); } catch (NumberFormatException e) { return msg(player, "금액은 정수여야 합니다.", false); } PlayerProfile profile = plugin.profile(player.getUniqueId()); if (profile == null) return true; profiles.donateGoodDeed(profile, amount, LocalDate.now().toString()).whenComplete((d, e) -> main(() -> msg(player, e == null ? "기부 선행 완료 · " + deedsText(d) : "하루 1회, 최소 500원이 필요합니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("project")) { profiles.claimPublicProjectDeed(player.getUniqueId()).whenComplete((d, e) -> main(() -> msg(player, e == null ? "공공 프로젝트 선행 완료" : "완료된 길드 공공 프로젝트가 필요합니다.", e == null))); return true; }
        if (args[1].equalsIgnoreCase("escort")) { if (!near(player, 24, 0, 12)) return msg(player, "후반 콘텐츠 월드 호위 출발점에서 시작하세요.", false); skyCheckpoints.put(player.getUniqueId(), -1); return msg(player, "호위 시작: 동쪽 마을 종까지 이동하세요.", true); }
        return msg(player, "/mp deeds delivery|help|donate <금액>|project|escort|status", true);
    }

    private boolean heaven(Player player, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("enter")) return msg(player, "/mp heaven enter", true);
        profiles.goodDeeds(player.getUniqueId()).whenComplete((deeds, error) -> main(() -> {
            if (error != null || !deeds.heavenUnlocked()) { msg(player, "서로 다른 선행 3종 이상, 총 10회가 필요합니다. 돈만으로는 열리지 않습니다.", false); return; }
            ItemStack wings = new ItemStack(Material.ELYTRA); wings.editMeta(meta -> { meta.displayName(Component.text("하늘나라 날개", NamedTextColor.AQUA)); meta.getPersistentDataContainer().set(wingsKey, PersistentDataType.BYTE, (byte) 1); });
            String grant = "heaven-wings:" + player.getUniqueId(); wings.editPersistentDataContainer(data -> data.set(plugin.grantKey(), PersistentDataType.STRING, grant));
            profiles.claimHeavenStar(player.getUniqueId(), 0, "UNLOCK", grant, wings.serializeAsBytes()).whenComplete((v, claimError) -> main(() -> {
                if (claimError == null) plugin.deliverPendingGrants(player);
                player.teleport(new Location(world, 0.5, 101, 80.5)); skyCheckpoints.put(player.getUniqueId(), 0); msg(player, "하늘나라 입장 · 날개를 착용하세요.", true);
            }));
        })); return true;
    }

    private boolean warrior(Player player, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) { profiles.warriorPath(player.getUniqueId()).whenComplete((path, e) -> main(() -> msg(player, e == null ? path.orElse("전투 직업 미선택") : "직업 조회 실패", e == null))); return true; }
        if (!args[1].equalsIgnoreCase("choose") || args.length != 3) return msg(player, "/mp warrior choose warrior|gladiator|hunter|mage", true);
        String path = args[2].toUpperCase(Locale.ROOT); profiles.chooseWarriorPath(player.getUniqueId(), path).whenComplete((v, e) -> main(() -> { if (e == null) warriorPaths.put(player.getUniqueId(), path); msg(player, e == null ? "전투 직업 선택: " + path : "직업은 한 번만 선택할 수 있습니다.", e == null); })); return true;
    }

    private boolean consume(Player player, String kind, String category, ItemStack source, int quantity, String success) {
        if (source.getAmount() < quantity || !plugin.tryLock(player.getUniqueId())) return msg(player, source.getAmount() < quantity ? "수량이 부족합니다." : "다른 작업이 진행 중입니다.", false);
        String id = UUID.randomUUID().toString(); ItemStack expected = source.clone(); expected.setAmount(quantity);
        ProfileStore.EndgameIntent intent = new ProfileStore.EndgameIntent(id, player.getUniqueId(), kind, expected.serializeAsBytes(), category, quantity, "PREPARED");
        profiles.prepareEndgameIntent(intent).whenComplete((v, prepareError) -> main(() -> {
            if (prepareError != null || !player.isOnline() || !source.isSimilar(expected) || source.getAmount() < quantity) { profiles.cancelEndgameIntent(id); plugin.unlock(player.getUniqueId()); msg(player, "거래 준비 실패", false); return; }
            source.editPersistentDataContainer(data -> data.set(intentKey, PersistentDataType.STRING, id)); player.saveData();
            profiles.markEndgameRemoving(id).whenComplete((v2, markError) -> main(() -> {
                if (markError != null) { source.editPersistentDataContainer(data -> data.remove(intentKey)); profiles.cancelEndgameIntent(id); plugin.unlock(player.getUniqueId()); msg(player, "거래 잠금 실패", false); return; }
                source.setAmount(source.getAmount() - quantity); source.editPersistentDataContainer(data -> data.remove(intentKey)); player.saveData();
                profiles.completeEndgameIntent(id).whenComplete((result, completeError) -> main(() -> { if (completeError == null && result.dragon() != null) dragons.put(player.getUniqueId(), result.dragon()); plugin.unlock(player.getUniqueId()); msg(player, completeError == null ? success : "재접속하면 거래가 복구됩니다.", completeError == null); }));
            }));
        })); return true;
    }

    private void activate(ProfileStore.EndgameSession session, Set<UUID> members) {
        if (runs.values().stream().anyMatch(run -> run.session.slot() == session.slot())) { plugin.getLogger().severe("중복 후반 콘텐츠 슬롯 거부: " + session.slot()); return; }
        Run run = new Run(session, members); runs.put(session.id(), run); members.forEach(id -> playerRuns.put(id, session.id()));
        buildStage(run); int chunk = baseX(session.slot()) >> 4; for (int x = chunk - 2; x <= chunk + 2; x++) for (int z = -2; z <= 2; z++) world.setChunkForceLoaded(x, z, true);
    }

    private void buildStage(Run run) {
        despawn(run); int bx = baseX(run.session.slot());
        for (int x = bx - SLOT_RADIUS; x <= bx + SLOT_RADIUS; x++) for (int z = -SLOT_RADIUS; z <= SLOT_RADIUS; z++) for (int y = Y; y <= Y + 14; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
        Material floor = switch (run.session.content()) { case "TRASH" -> Material.MOSS_BLOCK; case "PIRATE" -> Material.DARK_OAK_PLANKS; case "ANUBIS" -> Material.SANDSTONE; default -> towerFloor(run.session.aux()); };
        for (int x = bx - 20; x <= bx + 20; x++) for (int z = -20; z <= 20; z++) world.getBlockAt(x, Y, z).setType(floor, false);
        for (int i = -20; i <= 20; i++) { world.getBlockAt(bx + i, Y + 1, -20).setType(Material.BARRIER, false); world.getBlockAt(bx + i, Y + 1, 20).setType(Material.BARRIER, false); world.getBlockAt(bx - 20, Y + 1, i).setType(Material.BARRIER, false); world.getBlockAt(bx + 20, Y + 1, i).setType(Material.BARRIER, false); }
        decorate(run, bx);
        String stage = run.session.stage(); int remaining;
        if (run.session.content().equals("TRASH")) { if (stage.equals("VERMIN")) { remaining = 6 - run.session.progress(); for (int i=0;i<remaining;i++) mob(run, i%2==0 ? Silverfish.class : CaveSpider.class, "해충", 24); } else mob(run, Zombie.class, "쓰레기 군주", 100); }
        else if (run.session.content().equals("PIRATE")) { if (stage.equals("DECK")) { remaining=6-run.session.progress(); for(int i=0;i<remaining;i++) mob(run, Drowned.class,"해적 선원",30); } else if(stage.equals("CAPTAIN")) mob(run, WitherSkeleton.class,"해적 선장",120); else if(stage.equals("TREASURE")) world.getBlockAt(bx, Y+1, 8).setType(Material.CHEST); }
        else if (run.session.content().equals("ANUBIS")) { if (stage.equals("GLYPHS")) { for(int i=0;i<3;i++) world.getBlockAt(bx-6+i*6,Y+1,8).setType(i==run.session.progress()?Material.CHISELED_SANDSTONE:Material.SANDSTONE); for(int z=-4;z<=4;z++)for(int y=Y+1+run.session.progress()*2;y<=Y+6;y++)world.getBlockAt(bx+12,y,z).setType(Material.IRON_BARS); } else if(stage.equals("MUMMIES")) { remaining=6-run.session.progress(); for(int i=0;i<remaining;i++) mob(run,Husk.class,"미라",36); } else if(stage.equals("BOSS")) mob(run,Husk.class,"아누비스",150); }
        else { int floorNumber=run.session.aux(), required=floorNumber%10==0?1:Math.min(6,2+(floorNumber-1)/10), left=required-run.session.progress(); for(int i=0;i<left;i++) mob(run, floorNumber%10==0?WitherSkeleton.class:floorNumber>40?Phantom.class:floorNumber>30?Husk.class:floorNumber>20?Skeleton.class:Zombie.class, floorNumber%10==0?floorNumber+"층 수호자":floorNumber+"층 적", floorNumber%10==0?120:24+floorNumber); }
        run.phaseSince = System.currentTimeMillis();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Run run : new ArrayList<>(runs.values())) {
            String stage = run.session.stage();
            if (stage.equals("APPROACH")) { cannon(run); if (now-run.phaseSince>=10000) transition(run,"APPROACH","DECK",0); }
            if (stage.equals("STORM")) { run.members.stream().map(Bukkit::getPlayer).filter(p->p!=null&&p.getWorld().equals(world)).forEach(p->{p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,40,0,true,false));p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,40,1,true,false));}); if(now-run.phaseSince>=10000) transition(run,"STORM","GLYPHS",0); }
            if (run.session.content().equals("TRASH") && stage.equals("VERMIN")) run.members.stream().map(Bukkit::getPlayer).filter(p->p!=null&&inside(run,p.getLocation())).forEach(p->{ if (!hasMask(p)) { p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,60,0,true,false)); p.addPotionEffect(new PotionEffect(PotionEffectType.POISON,40,0,true,false)); } });
        }
        for (Player player : world.getPlayers()) {
            boolean sky = inSky(player.getLocation()); ItemStack chest = player.getInventory().getChestplate(); boolean wings = chest != null && chest.getPersistentDataContainer().has(wingsKey, PersistentDataType.BYTE);
            player.setAllowFlight(player.getGameMode()==GameMode.CREATIVE||player.getGameMode()==GameMode.SPECTATOR||sky&&wings); if (sky && wings) dragonEffect(player);
            Integer escort = skyCheckpoints.get(player.getUniqueId()); if (escort != null && escort == -1 && near(player,42,0,8)) { skyCheckpoints.remove(player.getUniqueId()); profiles.recordEscortDeed(player.getUniqueId(), LocalDate.now().toString()).whenComplete((d,e)->main(()->msg(player,e==null?"호위 선행 완료":"오늘 호위는 이미 완료했습니다.",e==null))); }
        }
    }

    private void cannon(Run run) {
        for (UUID id : run.members) { Player player=Bukkit.getPlayer(id); if(player==null||!inside(run,player.getLocation())) continue; Arrow arrow=world.spawn(player.getLocation().clone().add(12,5,0),Arrow.class); arrow.setVelocity(player.getLocation().toVector().subtract(arrow.getLocation().toVector()).normalize().multiply(1.2)); arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED); arrow.getPersistentDataContainer().set(sessionKey,PersistentDataType.STRING,run.session.id()); }
    }

    @EventHandler(ignoreCancelled=true) public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || event.getTo()==null || !event.getTo().getWorld().equals(world)) return;
        Player player=event.getPlayer(); int slot=slotAt(event.getTo()); if(slot>=0){Run run=runs.values().stream().filter(r->r.session.slot()==slot).findFirst().orElse(null); if(run==null||!run.members.contains(player.getUniqueId())){event.setCancelled(true);player.teleport(new Location(world,.5,Y+1,.5));}}
        if(inSky(event.getTo())) { int checkpoint=skyCheckpoint(event.getTo()); int current=skyCheckpoints.getOrDefault(player.getUniqueId(),0); if(checkpoint==current+1){skyCheckpoints.put(player.getUniqueId(),checkpoint); if(checkpoint>=5) claimStar(player,checkpoint);} }
    }

    @EventHandler(ignoreCancelled=true) public void onDamage(EntityDamageByEntityEvent event) {
        boolean victimPlayer=event.getEntity() instanceof Player; String session=event.getEntity().getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING); Run run=session==null&&victimPlayer?runAt(event.getEntity().getLocation()):runs.get(session); if(run==null)return;
        Entity source=event.getDamager(); if(source instanceof Projectile projectile&&projectile.getShooter() instanceof Entity shooter)source=shooter; Player attacker=source instanceof Player p?p:null; String sourceSession=source.getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING);
        if(!ProfileStore.allowsEndgameDamage(run.members,attacker==null?null:attacker.getUniqueId(),sourceSession,run.session.id(),victimPlayer)){event.setCancelled(true);return;} if(victimPlayer)return;
        if(run.session.content().equals("TRASH")&&run.session.stage().equals("VERMIN")&&!attacker.getInventory().getItemInMainHand().getPersistentDataContainer().has(sprayerKey,PersistentDataType.BYTE)){event.setCancelled(true);attacker.sendActionBar(Component.text("해충 살충기를 사용하세요.",NamedTextColor.RED));return;}
        String path=warriorPaths.get(attacker.getUniqueId());if(path!=null)switch(path){case "WARRIOR"->event.setDamage(event.getDamage()*1.08);case "GLADIATOR"->event.setDamage(event.getDamage()*(attacker.getHealth()<attacker.getAttribute(Attribute.MAX_HEALTH).getValue()/2?1.15:1));case "HUNTER"->{if(event.getDamager() instanceof Arrow)event.setDamage(event.getDamage()*1.12);}case "MAGE"->{if(attacker.getInventory().getItemInMainHand().getType()==Material.BLAZE_ROD)event.setDamage(event.getDamage()*1.12);}}
    }

    @EventHandler public void onDeath(EntityDeathEvent event) {
        String id=event.getEntity().getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING); if(id==null)return; event.getDrops().clear();event.setDroppedExp(0);Run run=runs.get(id);if(run==null)return;run.entities.remove(event.getEntity().getUniqueId());
        String stage=run.session.stage(); if(run.session.content().equals("TOWER")){profiles.advanceTower(id,week(),rewards(run,"영웅의 별",Material.NETHER_STAR)).whenComplete((advance,error)->main(()->{if(error!=null)return; if(advance.completed()){run.members.forEach(m->Optional.ofNullable(Bukkit.getPlayer(m)).ifPresent(plugin::deliverPendingGrants));close(run,false);}else{run.session=new ProfileStore.EndgameSession(run.session.id(),run.session.owner(),run.session.groupKey(),run.session.scope(),run.session.content(),run.session.slot(),"FLOOR",advance.progress(),advance.nextFloor(),"ACTIVE",run.session.startedAt());buildStage(run);}}));return;}
        int required=stage.equals("VERMIN")||stage.equals("DECK")||stage.equals("MUMMIES")?6:1;String next=switch(stage){case"VERMIN"->"BOSS";case"DECK"->"CAPTAIN";case"CAPTAIN"->"TREASURE";case"MUMMIES"->"BOSS";default->"DONE";};profiles.recordEndgameObjective(id,stage,required,next).whenComplete((updated,error)->main(()->{if(error!=null)return;run.session=updated;if(updated.stage().equals("DONE"))complete(run);else if(!updated.stage().equals(stage))buildStage(run);}));
    }

    @EventHandler(ignoreCancelled=true) public void onInteract(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getClickedBlock()==null||!event.getClickedBlock().getWorld().equals(world))return;int slot=slotAt(event.getClickedBlock().getLocation());if(slot<0)return;Run run=runs.values().stream().filter(r->r.session.slot()==slot).findFirst().orElse(null);if(run==null||!run.members.contains(event.getPlayer().getUniqueId())){event.setCancelled(true);return;}event.setCancelled(true);
        if(run.session.stage().equals("TREASURE")&&event.getClickedBlock().getType()==Material.CHEST){complete(run);return;}
        if(run.session.stage().equals("GLYPHS")&&event.getClickedBlock().getType()==Material.CHISELED_SANDSTONE){profiles.recordEndgameObjective(run.session.id(),"GLYPHS",3,"MUMMIES").whenComplete((updated,error)->main(()->{if(error!=null)return;run.session=updated;buildStage(run);}));}
    }

    private void complete(Run run) { profiles.completeEndgame(run.session.id(),rewards(run,run.session.content()+" 전리품",Material.DIAMOND)).whenComplete((done,error)->main(()->{if(error!=null)return;run.members.forEach(id->Optional.ofNullable(Bukkit.getPlayer(id)).ifPresent(plugin::deliverPendingGrants));close(run,false);})); }
    private List<ProfileStore.BossReward> rewards(Run run,String name,Material material){List<ProfileStore.BossReward> result=new ArrayList<>();for(UUID id:run.members){String grant=run.session.id()+":"+id+":"+material;ItemStack item=new ItemStack(material);item.editMeta(meta->meta.displayName(Component.text(name,NamedTextColor.GOLD)));item.editPersistentDataContainer(data->data.set(plugin.grantKey(),PersistentDataType.STRING,grant));result.add(new ProfileStore.BossReward(id,grant,item.serializeAsBytes()));}return result;}
    private void transition(Run run,String expected,String next,int aux){profiles.transitionEndgame(run.session.id(),expected,next,aux).whenComplete((updated,error)->main(()->{if(error==null){run.session=updated;buildStage(run);}}));}
    private void close(Run run,boolean teleport){despawn(run);runs.remove(run.session.id());run.members.forEach(id->{playerRuns.remove(id,run.session.id());Player p=Bukkit.getPlayer(id);if(p!=null&&teleport)p.teleport(new Location(world,.5,Y+1,.5));});int chunk=baseX(run.session.slot())>>4;for(int x=chunk-2;x<=chunk+2;x++)for(int z=-2;z<=2;z++)world.setChunkForceLoaded(x,z,false);}
    private void despawn(Run run){run.entities.forEach(id->{Entity entity=Bukkit.getEntity(id);if(entity!=null)entity.remove();});run.entities.clear();}
    private void mob(Run run,Class<? extends LivingEntity> type,String name,double health){int bx=baseX(run.session.slot());LivingEntity mob=world.spawn(new Location(world,bx-10+Math.random()*20,Y+1,-10+Math.random()*20),type);mob.customName(Component.text(name,NamedTextColor.RED));mob.setCustomNameVisible(true);mob.setPersistent(true);mob.getPersistentDataContainer().set(sessionKey,PersistentDataType.STRING,run.session.id());mob.getPersistentDataContainer().set(roleKey,PersistentDataType.STRING,name);if(mob.getAttribute(Attribute.MAX_HEALTH)!=null)mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);mob.setHealth(Math.min(health,mob.getAttribute(Attribute.MAX_HEALTH).getValue()));run.entities.add(mob.getUniqueId());}

    private void buildPersistent(){for(int x=-48;x<=48;x++)for(int z=-48;z<=128;z++)world.getBlockAt(x,Y,z).setType(z>=60?Material.QUARTZ_BLOCK:z>=-30?Material.STONE_BRICKS:Material.SPRUCE_PLANKS,false);for(int x=-20;x<=20;x++)for(int z=70;z<=120;z++)world.getBlockAt(x,100,z).setType(Material.QUARTZ_BLOCK,false);for(int i=0;i<5;i++){world.getBlockAt(-12+i*6,101,82+i*8).setType(Material.SEA_LANTERN);world.getBlockAt(-9+i*5,103+i*2,86+i*7).setType(Material.IRON_BARS);}for(int x:new int[]{-32,0,32})for(int z:new int[]{-18,18})hut(x,z);world.getBlockAt(42,Y+1,8).setType(Material.BELL);world.getBlockAt(24,Y+1,12).setType(Material.GOLD_BLOCK);}
    private void decorate(Run run,int bx){switch(run.session.content()){case"TRASH"->{for(int x=-14;x<=14;x+=7)for(int z=-14;z<=14;z+=7){world.getBlockAt(bx+x,Y+1,z).setType((x+z)%2==0?Material.COMPOSTER:Material.COBWEB);world.getBlockAt(bx+x,Y+2,z).setType(Material.BROWN_MUSHROOM_BLOCK);}}case"PIRATE"->{for(int i=-18;i<=18;i++){world.getBlockAt(bx+i,Y+1,-18).setType(Material.DARK_OAK_FENCE);world.getBlockAt(bx+i,Y+1,18).setType(Material.DARK_OAK_FENCE);}for(int y=Y+1;y<=Y+12;y++)world.getBlockAt(bx,y,0).setType(Material.STRIPPED_DARK_OAK_LOG);for(int z=-12;z<=12;z+=8){world.getBlockAt(bx-17,Y+1,z).setType(Material.DISPENSER);world.getBlockAt(bx+17,Y+1,z).setType(Material.DISPENSER);}}case"ANUBIS"->{for(int z=-16;z<=16;z+=4)for(int x=-16;x<=16;x++)if((x+z/4)%5!=0)world.getBlockAt(bx+x,Y+1,z).setType(Material.CUT_SANDSTONE);for(int x=-14;x<=14;x+=7)world.getBlockAt(bx+x,Y,trapZ(x)).setType(Material.MAGMA_BLOCK);}default->{for(int y=Y+1;y<=Y+6;y++)for(int i=-20;i<=20;i+=40){world.getBlockAt(bx+i,y,-20).setType(Material.POLISHED_BLACKSTONE_BRICKS);world.getBlockAt(bx+i,y,20).setType(Material.POLISHED_BLACKSTONE_BRICKS);}}}}
    private int trapZ(int x){return x%2==0?6:-6;}
    private void hut(int cx,int cz){for(int x=cx-6;x<=cx+6;x++)for(int z=cz-5;z<=cz+5;z++)for(int y=Y+1;y<=Y+5;y++)if(x==cx-6||x==cx+6||z==cz-5||z==cz+5||y==Y+5)world.getBlockAt(x,y,z).setType(y==Y+5?Material.DARK_OAK_PLANKS:Material.STONE_BRICKS,false);world.getBlockAt(cx,Y+1,cz-5).setType(Material.AIR);world.getBlockAt(cx,Y+2,cz-5).setType(Material.AIR);}
    private void protect(){RegionManager regions=WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));if(regions==null)throw new IllegalStateException("후반 콘텐츠 WorldGuard를 열 수 없습니다.");GlobalProtectedRegion global=regions.getRegion("__global__") instanceof GlobalProtectedRegion found?found:new GlobalProtectedRegion("__global__");global.setFlag(Flags.BLOCK_BREAK,StateFlag.State.DENY);global.setFlag(Flags.BLOCK_PLACE,StateFlag.State.DENY);for(String name:List.of("sit","playersit","pose","crawl")){Object flag=WorldGuard.getInstance().getFlagRegistry().get(name);if(flag instanceof StateFlag state)global.setFlag(state,StateFlag.State.DENY);}regions.addRegion(global);try{regions.save();}catch(Exception e){throw new IllegalStateException("후반 콘텐츠 보호 저장 실패",e);}}
    private void claimStar(Player player,int node){Material type=node==5?Material.AMETHYST_SHARD:Material.NETHER_STAR;ItemStack item=new ItemStack(type);String grant="heaven:"+week()+":"+node+":"+player.getUniqueId();item.editMeta(meta->meta.displayName(Component.text(node==5?"희귀 별조각":"하늘 별",NamedTextColor.LIGHT_PURPLE)));item.editPersistentDataContainer(data->data.set(plugin.grantKey(),PersistentDataType.STRING,grant));profiles.claimHeavenStar(player.getUniqueId(),node,week(),grant,item.serializeAsBytes()).whenComplete((v,e)->main(()->{if(e==null){plugin.deliverPendingGrants(player);msg(player,"공중 장애물 보상 획득",true);}}));}
    private void dragonEffect(Player player){ProfileStore.Dragon dragon=dragons.get(player.getUniqueId());if(dragon==null||!dragon.stage().equals("ADULT"))return;switch(dragon.trait()){case"SEA"->player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,60,0,true,false));case"MINERAL"->player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,240,0,true,false));case"SKY"->player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,60,0,true,false));default->player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,60,0,true,false));}}
    private String food(Material material){if(Set.of(Material.COD,Material.SALMON,Material.TROPICAL_FISH).contains(material))return"FISH";if(Set.of(Material.CARROT,Material.POTATO,Material.BEETROOT).contains(material))return"VEGETABLE";if(Set.of(Material.APPLE,Material.MELON_SLICE,Material.SWEET_BERRIES).contains(material))return"FRUIT";if(Set.of(Material.BEEF,Material.PORKCHOP,Material.CHICKEN,Material.MUTTON).contains(material))return"MEAT";if(Set.of(Material.IRON_NUGGET,Material.GOLD_NUGGET,Material.AMETHYST_SHARD).contains(material))return"MINERAL";if(Set.of(Material.BREAD,Material.COOKED_BEEF,Material.PUMPKIN_PIE).contains(material))return"COOKING";return null;}
    private void main(Runnable task){Bukkit.getScheduler().runTask(plugin,task);}
    private Run run(Player player){String id=playerRuns.get(player.getUniqueId());return id==null?null:runs.get(id);}
    private Run runAt(Location location){int slot=slotAt(location);return slot<0?null:runs.values().stream().filter(run->run.session.slot()==slot).findFirst().orElse(null);}
    private Location spawn(ProfileStore.EndgameSession session){return new Location(world,baseX(session.slot())+.5,Y+1,.5);}
    private int baseX(int slot){return SLOT_X+slot*SLOT_GAP;}
    private int slotAt(Location location){if(!location.getWorld().equals(world)||location.getBlockX()<SLOT_X-SLOT_RADIUS)return-1;int slot=Math.round((location.getBlockX()-SLOT_X)/(float)SLOT_GAP);return Math.abs(location.getBlockX()-baseX(slot))<=SLOT_RADIUS&&Math.abs(location.getBlockZ())<=SLOT_RADIUS?slot:-1;}
    private boolean inside(Run run,Location location){return slotAt(location)==run.session.slot();}
    private boolean inSky(Location l){return l.getWorld().equals(world)&&l.getY()>=95&&l.getX()>=-24&&l.getX()<=24&&l.getZ()>=65&&l.getZ()<=125;}
    private int skyCheckpoint(Location l){if(!inSky(l))return 0;for(int i=1;i<=5;i++)if(l.distanceSquared(new Location(world,-15+i*6,101,74+i*8))<16)return i;return 0;}
    private boolean near(Player p,double x,double z,double distance){return p.getWorld().equals(world)&&p.getLocation().distanceSquared(new Location(world,x+.5,Y+1,z+.5))<=distance*distance;}
    private boolean hasMask(Player p){ItemStack helmet=p.getInventory().getHelmet();return helmet!=null&&helmet.getPersistentDataContainer().has(maskKey,PersistentDataType.BYTE);}
    private ItemStack findMarked(Player p,String id){for(ItemStack item:p.getInventory().getContents())if(item!=null&&id.equals(item.getPersistentDataContainer().get(intentKey,PersistentDataType.STRING)))return item;return null;}
    private Material towerFloor(int floor){return floor<=10?Material.STONE_BRICKS:floor<=20?Material.MOSSY_COBBLESTONE:floor<=30?Material.SANDSTONE:floor<=40?Material.PRISMARINE:Material.BLACKSTONE;}
    private String week(){return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();}
    private String recordsText(List<ProfileStore.TowerRecord> records){if(records.isEmpty())return"이번 주 탑 기록 없음";StringBuilder out=new StringBuilder("이번 주 영웅의 탑");for(ProfileStore.TowerRecord r:records)out.append("\n").append(r.groupKey(),0,Math.min(8,r.groupKey().length())).append(" · ").append(r.highestFloor()).append("층 · ").append(r.partySize()).append("명");return out.toString();}
    private String deedsText(ProfileStore.GoodDeeds d){return "납품 "+d.delivery()+" · 도움 "+d.npcHelp()+" · 기부 "+d.donation()+" · 호위 "+d.escort()+" · 공공 "+d.publicProject()+" · 총 "+d.total()+" · 하늘 "+(d.heavenUnlocked()?"해금":"잠김");}
    private String root(Throwable error){Throwable current=error;while(current.getCause()!=null)current=current.getCause();return current.getMessage()==null?current.getClass().getSimpleName():current.getMessage();}
    private boolean msg(Player player,String text,boolean good){player.sendMessage(Component.text(text,good?NamedTextColor.AQUA:NamedTextColor.RED));return true;}
    private static final class Run{private ProfileStore.EndgameSession session;private final Set<UUID> members;private final Set<UUID> entities=new HashSet<>();private long phaseSince;private Run(ProfileStore.EndgameSession session,Set<UUID> members){this.session=session;this.members=members;}}
}
