package kr.hyuni.marketplay;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class HubBuilder {
    static final String WORLD = "mp_lobby";
    static final int FLOOR_Y = 64;
    private static final int BUILD_LIMIT = 96;
    private static final int TRAVEL_GATE = 76;
    private static final Material MAP_VERSION = Material.WAXED_OXIDIZED_COPPER;
    private static final String NPC_KEY = "marketplay_hub_role";
    static final LocationKey MARKET = new LocationKey(0, 65, -17);
    static final LocationKey SELL = new LocationKey(3, 65, -17);
    static final Area MOUNTAIN = new Area(-24, -24, -10, -8);
    static final Area GROVE = new Area(-23, -7, -12, 8);
    static final Area FIELD = new Area(10, -8, 24, 8);
    static final Area RANCH = new Area(-24, 9, -10, 24);
    static final Area RIVER = new Area(9, 9, 24, 24);
    static final Area SOCIAL = new Area(-9, -24, 9, 24);
    static final List<Node> NODES = List.of(
            new Node("apple", "사과나무", new LocationKey(-18, 66, 0), Material.APPLE, Skill.FORAGING, "old_net"),
            new Node("oak_log", "참나무", new LocationKey(-20, 65, 3), Material.OAK_LOG, Skill.WOODCUTTING, "old_axe"),
            new Node("wheat", "밀밭", new LocationKey(18, 65, 0), Material.WHEAT, Skill.FARMING, "old_hoe"),
            new Node("wool", "목장", new LocationKey(-17, 65, 17), Material.WHITE_WOOL, Skill.FORAGING, "old_shears"),
            new Node("iron_ore", "초보 광맥", new LocationKey(-17, 65, -17), Material.RAW_IRON, Skill.MINING, "old_pickaxe")
    );

    private final MarketPlayPlugin plugin;
    private final NamespacedKey displayKey;
    private World world;

    HubBuilder(MarketPlayPlugin plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, "hub_display");
    }

    boolean ensure() {
        boolean existed = Files.exists(plugin.getServer().getWorldContainer().toPath().resolve(WORLD).resolve("level.dat"));
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = Bukkit.createWorld(new WorldCreator(WORLD).type(WorldType.FLAT).generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}"));
        if (world == null) throw new IllegalStateException("시장놀이 로비 월드를 만들 수 없습니다.");
        Block marker = world.getBlockAt(0, FLOOR_Y - 2, 0);
        if (marker.getType() != Material.LODESTONE) {
            if (existed) throw new IllegalStateException("기존 로비 월드에 설치 표식이 없어 덮어쓰지 않습니다.");
            paste(world);
            plugin.getLogger().info("FAWE schematic으로 시장놀이 중앙광장을 설치했습니다.");
        }
        if (world.getBlockAt(1, FLOOR_Y - 2, 0).getType() != MAP_VERSION) buildExpansion();
        protect(world);
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(BUILD_LIMIT * 2.0);
        world.getWorldBorder().setWarningDistance(0);
        world.setSpawnLocation(new Location(world, 0.5, FLOOR_Y + 1, 10.5));
        updateDisplays(world);
        spawnNpcs();
        return true;
    }

    World world() { return world; }
    Location spawn() { return new Location(world, .5, FLOOR_Y + 1, 10.5, 180, 0); }
    void teleport(org.bukkit.entity.Player player) { player.teleportAsync(spawn()); }

    private boolean isEmpty(World world) {
        for (int x = -25; x <= 25; x++) for (int z = -25; z <= 25; z++)
            for (int y = FLOOR_Y - 2; y <= FLOOR_Y + 16; y++)
                if (!world.getBlockAt(x, y, z).isEmpty()) return false;
        return true;
    }

    private void paste(World world) {
        try (InputStream input = plugin.getResource("maps/central-plaza-v1.schem");
             var reader = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(require(input))) {
            try (var clipboard = reader.read(); var holder = new ClipboardHolder(clipboard);
                 var edit = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operations.complete(holder.createPaste(edit).to(BlockVector3.at(-24, FLOOR_Y - 2, -24)).ignoreAirBlocks(false).build());
                edit.flushQueue();
            }
        } catch (Exception error) {
            throw new IllegalStateException("중앙광장 schematic 설치 실패", error);
        }
    }

    private InputStream require(InputStream input) {
        if (input == null) throw new IllegalStateException("maps/central-plaza-v1.schem 리소스가 없습니다.");
        return input;
    }

    private void buildExpansion() {
        for (int x = -BUILD_LIMIT; x <= BUILD_LIMIT; x++) for (int z = -BUILD_LIMIT; z <= BUILD_LIMIT; z++) {
            if (Math.abs(x) <= 25 && Math.abs(z) <= 25) continue;
            world.getBlockAt(x, 63, z).setType(Material.DIRT, false);
            world.getBlockAt(x, 64, z).setType(Material.GRASS_BLOCK, false);
            for (int y = 65; y <= 84; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
        road(-90, 90, 0, true);
        road(-90, 90, 0, false);
        outerPromenade();
        marketDistrict();
        housingDistrict();
        artDistrict();
        transitDistrict();
        communityDistrict();
        buildTravelGates();
        cornerGardens();
        world.getBlockAt(1, FLOOR_Y - 2, 0).setType(MAP_VERSION, false);
    }

    private void marketDistrict() {
        building(-22, -66, 20, 24, Material.BRICKS, Material.DARK_OAK_PLANKS);
        building(3, -66, 20, 24, Material.SANDSTONE, Material.CUT_SANDSTONE);
        building(28, -66, 20, 24, Material.STONE_BRICKS, Material.POLISHED_ANDESITE);
        workstations(-16, -54, List.of(Material.BARREL, Material.SMOKER, Material.CRAFTING_TABLE, Material.LOOM));
        workstations(9, -54, List.of(Material.CHEST, Material.LECTERN, Material.CARTOGRAPHY_TABLE, Material.ENDER_CHEST));
        workstations(34, -54, List.of(Material.ANVIL, Material.GRINDSTONE, Material.SMITHING_TABLE, Material.STONECUTTER));
        for (int x = -34; x <= 52; x += 8) {
            fill(x, 64, -35, x + 4, 64, -31, Material.SMOOTH_STONE);
            world.getBlockAt(x + 2, 65, -33).setType(Material.BARREL, false);
            fill(x, 66, -35, x + 4, 66, -35, Material.RED_WOOL);
        }
    }

    private void housingDistrict() {
        building(-70, -24, 22, 19, Material.OAK_PLANKS, Material.DARK_OAK_PLANKS);
        building(-70, 5, 22, 19, Material.BIRCH_PLANKS, Material.SPRUCE_PLANKS);
        building(-45, 31, 18, 18, Material.MUD_BRICKS, Material.MANGROVE_PLANKS);
        workstations(-63, -13, List.of(Material.RED_BED, Material.BARREL, Material.LOOM, Material.CRAFTING_TABLE));
        workstations(-63, 16, List.of(Material.FLOWER_POT, Material.CHISELED_BOOKSHELF, Material.CARTOGRAPHY_TABLE));
        for (int x = -74; x <= -28; x += 8) tree(x, 58 + Math.floorMod(x, 8));
    }

    private void artDistrict() {
        building(48, -24, 24, 22, Material.QUARTZ_BRICKS, Material.SMOOTH_QUARTZ);
        building(48, 6, 24, 22, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE);
        building(28, 34, 20, 18, Material.STONE_BRICKS, Material.COPPER_BLOCK);
        workstations(55, -12, List.of(Material.DECORATED_POT, Material.CHISELED_BOOKSHELF, Material.CARTOGRAPHY_TABLE, Material.LOOM));
        workstations(55, 18, List.of(Material.JUKEBOX, Material.NOTE_BLOCK, Material.LECTERN, Material.DECORATED_POT));
        for (int z = -18; z <= 22; z += 8) {
            world.getBlockAt(70, 65, z).setType(Material.SEA_LANTERN, false);
            world.getBlockAt(70, 66, z).setType(Material.GLASS, false);
        }
    }

    private void transitDistrict() {
        building(-20, 42, 18, 22, Material.STONE_BRICKS, Material.DEEPSLATE_TILES);
        building(3, 42, 18, 22, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS);
        workstations(-14, 54, List.of(Material.ENDER_CHEST, Material.LODESTONE, Material.RESPAWN_ANCHOR));
        workstations(9, 54, List.of(Material.BARREL, Material.CRAFTING_TABLE, Material.CAMPFIRE));
        fill(-26, 64, 30, 26, 64, 38, Material.SMOOTH_STONE);
        for (int x = -22; x <= 22; x += 11) world.getBlockAt(x, 65, 34).setType(Material.OAK_SIGN, false);
    }

    private void communityDistrict() {
        fill(-24, 64, 66, 24, 64, 74, Material.STONE_BRICKS);
        fill(-5, 64, 64, 5, 64, 80, Material.POLISHED_ANDESITE);
        fill(-22, 65, 68, -10, 65, 73, Material.OAK_PLANKS);
        fill(10, 65, 68, 22, 65, 73, Material.SPRUCE_PLANKS);
        for (int x = -20; x <= -12; x += 4) world.getBlockAt(x, 66, 70).setType(Material.LANTERN, false);
        for (int x = 12; x <= 20; x += 4) world.getBlockAt(x, 66, 70).setType(Material.LANTERN, false);
    }

    private void outerPromenade() {
        for (int p = -88; p <= 88; p++) for (int width = -2; width <= 2; width++) {
            world.getBlockAt(p, 64, -86 + width).setType(Material.SMOOTH_STONE, false);
            world.getBlockAt(p, 64, 86 + width).setType(Material.SMOOTH_STONE, false);
            world.getBlockAt(-86 + width, 64, p).setType(Material.SMOOTH_STONE, false);
            world.getBlockAt(86 + width, 64, p).setType(Material.SMOOTH_STONE, false);
        }
        for (int p = -84; p <= 84; p += 12) {
            lamp(p, -83); lamp(p, 83);
            lamp(-83, p); lamp(83, p);
        }
    }

    private void buildTravelGates() {
        portalAcrossZ(0, -TRAVEL_GATE, Material.DEEPSLATE_BRICKS, Material.SOUL_LANTERN);
        portalAcrossZ(0, TRAVEL_GATE, Material.MOSSY_STONE_BRICKS, Material.SEA_LANTERN);
        portalAcrossX(TRAVEL_GATE, -12, Material.RED_NETHER_BRICKS, Material.SHROOMLIGHT);
        portalAcrossX(TRAVEL_GATE, 0, Material.POLISHED_DEEPSLATE, Material.END_ROD);
        portalAcrossX(TRAVEL_GATE, 12, Material.PURPUR_BLOCK, Material.SEA_LANTERN);
        portalAcrossX(-TRAVEL_GATE, 0, Material.MUD_BRICKS, Material.LANTERN);
        fill(-5, 64, -80, 5, 64, -73, Material.POLISHED_DEEPSLATE);
        fill(-5, 64, 73, 5, 64, 80, Material.MOSSY_STONE_BRICKS);
        fill(73, 64, -18, 80, 64, 18, Material.POLISHED_ANDESITE);
        fill(-80, 64, -5, -73, 64, 5, Material.PACKED_MUD);
    }

    private void portalAcrossZ(int centerX, int z, Material frame, Material light) {
        fill(centerX - 6, 65, z, centerX - 6, 72, z, frame);
        fill(centerX + 6, 65, z, centerX + 6, 72, z, frame);
        fill(centerX - 6, 72, z, centerX + 6, 72, z, frame);
        world.getBlockAt(centerX - 6, 70, z).setType(light, false);
        world.getBlockAt(centerX + 6, 70, z).setType(light, false);
        world.getBlockAt(centerX, 72, z).setType(light, false);
        fill(centerX - 4, 65, z, centerX + 4, 71, z, Material.AIR);
    }

    private void portalAcrossX(int x, int centerZ, Material frame, Material light) {
        fill(x, 65, centerZ - 5, x, 72, centerZ - 5, frame);
        fill(x, 65, centerZ + 5, x, 72, centerZ + 5, frame);
        fill(x, 72, centerZ - 5, x, 72, centerZ + 5, frame);
        world.getBlockAt(x, 70, centerZ - 5).setType(light, false);
        world.getBlockAt(x, 70, centerZ + 5).setType(light, false);
        world.getBlockAt(x, 72, centerZ).setType(light, false);
        fill(x, 65, centerZ - 4, x, 71, centerZ + 4, Material.AIR);
    }

    private void cornerGardens() {
        for (int x : List.of(-90, 90)) for (int z : List.of(-90, 90)) {
            fill(x - 4, 64, z - 4, x + 4, 64, z + 4, Material.MOSS_BLOCK);
            tree(x, z);
            for (int dx : List.of(-5, 5)) for (int dz : List.of(-5, 5)) world.getBlockAt(x + dx, 65, z + dz).setType(Material.LANTERN, false);
        }
    }

    private void building(int x, int z, int width, int depth, Material wall, Material roof) {
        fill(x, 64, z, x + width, 64, z + depth, Material.SMOOTH_STONE);
        for (int y = 65; y <= 71; y++) for (int dx = 0; dx <= width; dx++) for (int dz = 0; dz <= depth; dz++)
            if (dx == 0 || dx == width || dz == 0 || dz == depth) world.getBlockAt(x + dx, y, z + dz).setType(wall, false);
        fill(x + 1, 72, z + 1, x + width - 1, 72, z + depth - 1, roof);
        fill(x + width / 2 - 1, 65, z, x + width / 2 + 1, 68, z, Material.AIR);
        for (int dx = 3; dx < width; dx += 5) world.getBlockAt(x + dx, 68, z + depth).setType(Material.GLASS_PANE, false);
    }

    private void workstations(int x, int z, List<Material> materials) {
        for (int i = 0; i < materials.size(); i++) world.getBlockAt(x + i * 2, 65, z).setType(materials.get(i), false);
    }

    private void road(int from, int to, int fixed, boolean xAxis) {
        for (int value = from; value <= to; value++) for (int side = -3; side <= 3; side++)
            world.getBlockAt(xAxis ? value : fixed + side, 64, xAxis ? fixed + side : value).setType(Material.POLISHED_ANDESITE, false);
    }

    private void lamp(int x, int z) {
        fill(x, 65, z, x, 67, z, Material.DARK_OAK_FENCE);
        world.getBlockAt(x, 68, z).setType(Material.LANTERN, false);
    }

    private void tree(int x, int z) {
        fill(x, 65, z, x, 70, z, Material.OAK_LOG);
        fill(x - 2, 69, z - 2, x + 2, 71, z + 2, Material.OAK_LEAVES);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) world.getBlockAt(x, y, z).setType(material, false);
    }

    private void protect(World world) {
        RegionManager regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regions == null) throw new IllegalStateException("WorldGuard region manager를 열 수 없습니다.");
        Area expanded = new Area(-BUILD_LIMIT, -BUILD_LIMIT, BUILD_LIMIT, BUILD_LIMIT);
        ProtectedRegion current = regions.getRegion("marketplay_lobby");
        BlockVector3 expectedMin = BlockVector3.at(expanded.minX(), FLOOR_Y - 2, expanded.minZ());
        BlockVector3 expectedMax = BlockVector3.at(expanded.maxX(), FLOOR_Y + 24, expanded.maxZ());
        if (current != null && (!current.getMinimumPoint().equals(expectedMin) || !current.getMaximumPoint().equals(expectedMax)))
            regions.removeRegion("marketplay_lobby");
        ProtectedRegion lobby = ensureRegion(regions, "marketplay_lobby", expanded, FLOOR_Y - 2, FLOOR_Y + 24);
        lobby.setFlag(Flags.USE, StateFlag.State.ALLOW);
        lobby.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        lobby.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        protectGsit(regions, lobby, List.of());
        try { regions.save(); } catch (Exception error) { throw new IllegalStateException("WorldGuard 보호 저장 실패", error); }
    }

    private ProtectedRegion ensureRegion(RegionManager regions, String id, Area area) {
        return ensureRegion(regions, id, area, FLOOR_Y - 2, FLOOR_Y + 16);
    }

    private ProtectedRegion ensureRegion(RegionManager regions, String id, Area area, int minY, int maxY) {
        BlockVector3 min = BlockVector3.at(area.minX(), minY, area.minZ());
        BlockVector3 max = BlockVector3.at(area.maxX(), maxY, area.maxZ());
        ProtectedRegion existing = regions.getRegion(id);
        if (existing != null && (!existing.getMinimumPoint().equals(min) || !existing.getMaximumPoint().equals(max)))
            throw new IllegalStateException(id + " 보호 구역 좌표가 예상과 다릅니다.");
        ProtectedRegion region = existing == null ? new ProtectedCuboidRegion(id, min, max) : existing;
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        if (existing == null) regions.addRegion(region);
        return region;
    }

    private void protectGsit(RegionManager regions, ProtectedRegion social, List<ProtectedRegion> work) {
        List<StateFlag> flags = List.of("sit", "playersit", "pose", "crawl").stream()
                .map(name -> WorldGuard.getInstance().getFlagRegistry().get(name))
                .filter(StateFlag.class::isInstance).map(StateFlag.class::cast).toList();
        if (flags.size() != 4) { plugin.getLogger().warning("GSit WorldGuard 플래그를 찾지 못해 자세 지역 제한을 건너뜁니다."); return; }
        ProtectedRegion global = regions.getRegion("__global__");
        if (global == null) { global = new GlobalProtectedRegion("__global__"); regions.addRegion(global); }
        for (StateFlag flag : flags) {
            global.setFlag(flag, StateFlag.State.DENY);
            social.setFlag(flag, StateFlag.State.ALLOW);
            work.forEach(region -> region.setFlag(flag, StateFlag.State.DENY));
        }
        social.setFlag(Flags.RIDE, StateFlag.State.ALLOW);
        social.setPriority(10);
        work.forEach(region -> region.setPriority(20));
    }

    private void spawnNpcs() {
        if (!CitizensAPI.hasImplementation()) throw new IllegalStateException("Citizens가 없어 로비 NPC를 만들 수 없습니다.");
        ArrayList<NPC> guides = new ArrayList<>();
        CitizensAPI.getNPCRegistry().forEach(npc -> { if (npc.data().has("rpgmaker-guide-dialogue")) guides.add(npc); });
        if (!guides.isEmpty()) {
            NPC guide = guides.getFirst();
            if (guide.isSpawned() && guide.getEntity().getType() != EntityType.PLAYER) guide.despawn();
            guide.setBukkitEntityType(EntityType.PLAYER);
            Location target = new Location(world, .5, 65, 8.5, 180, 0);
            target.getChunk().load();
            if (guide.isSpawned()) guide.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            else if (!guide.spawn(target)) throw new IllegalStateException("광장 안내인 Citizens NPC 생성 실패");
            guide.getEntity().setPersistent(false);
            guide.getOrAddTrait(LookClose.class).lookClose(true);
        }
        ensureNpc("market", "생활도구 상인", -7.5, 65, -33.5);
        ensureNpc("board", "시장 게시판 관리인", 7.5, 65, -33.5);
        ensureNpc("housing", "주택 안내원", -43.5, 65, .5);
        ensureNpc("art", "미술관 큐레이터", 43.5, 65, .5);
        ensureNpc("travel", "여행 안내원", .5, 65, 33.5);
        ensureNpc("adventure", "모험가 길드 안내원", 12.5, 65, 43.5);
        ensureNpc("restaurant", "레스토랑 지배인", -12.5, 65, -69.5);
        ensureNpc("guild", "상단 관리인", 12.5, 65, -69.5);
        CitizensAPI.getNPCRegistry().saveToStore();
    }

    private void ensureNpc(String role, String name, double x, double y, double z) {
        ArrayList<NPC> found = new ArrayList<>();
        CitizensAPI.getNPCRegistry().forEach(npc -> { if (role.equals(npc.data().get(NPC_KEY, ""))) found.add(npc); });
        NPC npc = found.isEmpty() ? CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name) : found.getFirst();
        found.stream().skip(1).forEach(NPC::destroy);
        if (npc.isSpawned() && npc.getEntity().getType() != EntityType.PLAYER) npc.despawn();
        npc.setBukkitEntityType(EntityType.PLAYER);
        npc.data().setPersistent(NPC_KEY, role);
        npc.data().setPersistent("rpgmaker-guide-dialogue", switch (role) {
            case "market" -> "시장놀이_시장안내";
            case "board" -> "시장놀이_게시판안내";
            case "housing" -> "시장놀이_주택안내";
            case "travel" -> "시장놀이_여행안내";
            case "adventure" -> "시장놀이_모험안내";
            default -> "시장놀이_시설안내";
        });
        npc.setProtected(true);
        npc.getOrAddTrait(LookClose.class).lookClose(true);
        Location target = new Location(world, x, y, z);
        target.getChunk().load();
        if (npc.isSpawned()) npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        else if (!npc.spawn(target)) throw new IllegalStateException(name + " Citizens NPC 생성 실패");
        npc.getEntity().setPersistent(false);
    }

    void updateDisplays(World world) {
        world.getNearbyEntities(new Location(world, 0, FLOOR_Y + 4, 0), 110, 24, 110).stream()
                .filter(TextDisplay.class::isInstance).map(TextDisplay.class::cast)
                .filter(display -> display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING))
                .forEach(TextDisplay::remove);
        display(world, new Location(world, -5.5, FLOOR_Y + 3.2, -16.7), "market",
                Component.text(plugin.marketText(), NamedTextColor.GOLD));
        display(world, new Location(world, 12.5, FLOOR_Y + 3.0, 8.5), "bulletin",
                Component.text(MarketText.bulletinPosts(plugin.bulletins()), NamedTextColor.AQUA));
        display(world, new Location(world, 0.5, FLOOR_Y + 3.0, 10.5), "welcome",
                Component.text("시장놀이 중앙광장\n분수 앞에서 안내인을 만나세요", NamedTextColor.YELLOW));
        display(world, new Location(world, .5, FLOOR_Y + 4, -34.5), "market-district", Component.text("시장 거리 · 도구 / 거래 / 상단", NamedTextColor.GOLD));
        display(world, new Location(world, -43.5, FLOOR_Y + 4, .5), "housing-district", Component.text("주택 거리 · 집 / 우편 / 가구", NamedTextColor.GREEN));
        display(world, new Location(world, 43.5, FLOOR_Y + 4, .5), "art-district", Component.text("문화 거리 · 그림 / 전시 / 공연", NamedTextColor.LIGHT_PURPLE));
        display(world, new Location(world, .5, FLOOR_Y + 4, 34.5), "travel-district", Component.text("여행소 · 채집소 / 탐험 지역", NamedTextColor.AQUA));
        display(world, new Location(world, .5, FLOOR_Y + 4, 74.5), "resource-exit", Component.text("남쪽 관문 → 자원 채집소", NamedTextColor.GREEN));
        display(world, new Location(world, .5, FLOOR_Y + 4, -74.5), "exploration-exit", Component.text("북쪽 관문 → 탐험과 사냥", NamedTextColor.RED));
        display(world, new Location(world, 74.5, FLOOR_Y + 4, -12.0), "dungeon-exit", Component.text("동쪽 붉은 관문 → 던전", NamedTextColor.RED));
        display(world, new Location(world, 74.5, FLOOR_Y + 4, 0.0), "tower-exit", Component.text("동쪽 중앙 관문 → 무한 탑", NamedTextColor.AQUA));
        display(world, new Location(world, 74.5, FLOOR_Y + 4, 12.0), "endgame-exit", Component.text("동쪽 보랏빛 관문 → 후반 마을", NamedTextColor.LIGHT_PURPLE));
        display(world, new Location(world, -74.5, FLOOR_Y + 4, .5), "west-gate", Component.text("서쪽 생활 관문 · 주택 / 우편 / 가구", NamedTextColor.GREEN));
    }

    private void display(World world, Location location, String id, Component text) {
        world.spawn(location, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setShadowed(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, id);
        });
    }

    static boolean contains(Area area, Location location) { return area.contains(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }

    record LocationKey(int x, int y, int z) {
        boolean matches(Block block) { return WORLD.equals(block.getWorld().getName()) && block.getX() == x && block.getY() == y && block.getZ() == z; }
    }

    record Node(String id, String name, LocationKey location, Material reward, Skill skill, String toolId) {}
}
