package kr.hyuni.marketplay;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ResourceWorldManager implements Listener {
    static final String WORLD = "mp_resources";
    private static final int Y = 64;
    private static final int LIMIT = 96;
    private static final Material MAP_VERSION = Material.WAXED_EXPOSED_COPPER;
    private static final String NPC_KEY = "marketplay_hub_role";
    private static final Area RIVER = new Area(34, 24, 82, 82);
    private static final List<Node> NODES = List.of(
            new Node("apple-west", "사과나무", -54, 66, -10, Material.OAK_LOG, Material.APPLE, Skill.FORAGING, "old_net"),
            new Node("apple-grove", "과수원 사과", -42, 66, 11, Material.OAK_LOG, Material.APPLE, Skill.FORAGING, "old_net"),
            new Node("berry", "달콤한 열매", -62, 65, 18, Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES, Skill.FORAGING, "old_net"),
            new Node("oak-north", "참나무", -52, 65, -25, Material.OAK_LOG, Material.OAK_LOG, Skill.WOODCUTTING, "old_axe"),
            new Node("oak-south", "큰 참나무", -35, 65, 22, Material.OAK_LOG, Material.OAK_LOG, Skill.WOODCUTTING, "old_axe"),
            new Node("wheat", "밀밭", 50, 65, -12, Material.WHEAT, Material.WHEAT, Skill.FARMING, "old_hoe"),
            new Node("carrot", "당근밭", 62, 65, 8, Material.CARROTS, Material.CARROT, Skill.FARMING, "old_hoe"),
            new Node("wool", "양털 작업대", -20, 65, 58, Material.LOOM, Material.WHITE_WOOL, Skill.FORAGING, "old_shears"),
            new Node("honey", "양봉장", -5, 66, 70, Material.BEE_NEST, Material.HONEYCOMB, Skill.FORAGING, "old_net"),
            new Node("iron", "철 광맥", 20, 65, -58, Material.IRON_ORE, Material.RAW_IRON, Skill.MINING, "old_pickaxe"),
            new Node("coal", "석탄 광맥", 38, 65, -70, Material.COAL_ORE, Material.COAL, Skill.MINING, "old_pickaxe"),
            new Node("deep-iron", "풍부한 철 광맥", 52, 65, -48, Material.DEEPSLATE_IRON_ORE, Material.RAW_IRON, Skill.MINING, "old_pickaxe")
    );

    private final MarketPlayPlugin plugin;
    private World world;

    ResourceWorldManager(MarketPlayPlugin plugin) { this.plugin = plugin; }

    void start() {
        validateLayout();
        boolean existed = Files.exists(plugin.getServer().getWorldContainer().toPath().resolve(WORLD).resolve("level.dat"));
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = Bukkit.createWorld(new WorldCreator(WORLD).type(WorldType.FLAT).generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}"));
        if (world == null) throw new IllegalStateException("자원 채집 월드를 만들 수 없습니다.");
        if (world.getBlockAt(0, Y - 2, 0).getType() != Material.LODESTONE) {
            if (existed) throw new IllegalStateException("기존 자원 월드에 설치 표식이 없어 덮어쓰지 않습니다.");
            build();
        } else if (world.getBlockAt(1, Y - 2, 0).getType() != MAP_VERSION) build();
        protect();
        spawnNpcs();
        world.setSpawnLocation(spawn());
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void stop() { }

    Location spawn() { return new Location(world, .5, Y + 1, .5, 180, 0); }

    void teleport(org.bukkit.entity.Player player) { player.teleportAsync(spawn()); }

    boolean isRiver(Location location) {
        return location.getWorld() != null && WORLD.equals(location.getWorld().getName())
                && RIVER.contains(location.getBlockX(), location.getBlockZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || !WORLD.equals(event.getClickedBlock().getWorld().getName())) return;
        if (event.getClickedBlock().getX() == 0 && event.getClickedBlock().getY() == 65 && event.getClickedBlock().getZ() == 3) {
            event.setCancelled(true); plugin.teleportLobby(event.getPlayer()); return;
        }
        if (event.getClickedBlock().getX() == 3 && event.getClickedBlock().getY() == 65 && event.getClickedBlock().getZ() == 0) {
            event.setCancelled(true); plugin.openHubMenu(event.getPlayer(), "resources"); return;
        }
        for (Node node : NODES) if (node.matches(event.getClickedBlock().getX(), event.getClickedBlock().getY(), event.getClickedBlock().getZ())) {
            event.setCancelled(true);
            plugin.harvest(event.getPlayer(), WORLD + ':' + node.id(), node.id(), node.name(), node.reward(), node.skill(), node.toolId());
            return;
        }
    }

    private void build() {
        fill(-LIMIT, Y - 2, -LIMIT, LIMIT, Y + 24, LIMIT, Material.AIR);
        fill(-LIMIT, Y - 1, -LIMIT, LIMIT, Y - 1, LIMIT, Material.DIRT);
        fill(-LIMIT, Y, -LIMIT, LIMIT, Y, LIMIT, Material.GRASS_BLOCK);
        boundary();
        road(-88, 88, 0, true); road(-88, 88, 0, false);
        station(); forest(); farm(); ranch(); mine(); river();
        NODES.forEach(node -> world.getBlockAt(node.x(), node.y(), node.z()).setType(node.block(), false));
        world.getBlockAt(0, Y - 2, 0).setType(Material.LODESTONE, false);
        world.getBlockAt(1, Y - 2, 0).setType(MAP_VERSION, false);
    }

    private void station() {
        fill(-10, 64, -10, 10, 64, 10, Material.SMOOTH_STONE);
        for (int x : List.of(-9, 9)) for (int z : List.of(-9, 9)) fill(x, 65, z, x, 70, z, Material.STRIPPED_OAK_LOG);
        fill(-9, 71, -9, 9, 71, 9, Material.DARK_OAK_SLAB);
        fill(-2, 65, -2, 2, 65, 2, Material.POLISHED_ANDESITE);
        world.getBlockAt(0, 66, 0).setType(Material.SEA_LANTERN, false);
        world.getBlockAt(0, 65, 3).setType(Material.LODESTONE, false);
        world.getBlockAt(3, 65, 0).setType(Material.BARREL, false);
    }

    private void forest() {
        fill(-86, 64, -34, -28, 64, 30, Material.MOSS_BLOCK);
        for (int x = -80; x <= -32; x += 12) for (int z = -28; z <= 24; z += 13) tree(x, z, Math.floorMod(x + z, 3));
        pond(-68, -2, 7);
        building(-34, -19, 11, 13, Material.OAK_PLANKS, Material.DARK_OAK_PLANKS);
        workstations(-30, -15, List.of(Material.CRAFTING_TABLE, Material.CARTOGRAPHY_TABLE, Material.COMPOSTER));
    }

    private void farm() {
        fill(28, 64, -34, 86, 64, 30, Material.DIRT);
        for (int x = 34; x <= 80; x++) for (int z = -27; z <= 23; z++) {
            if (x % 9 == 0) world.getBlockAt(x, 64, z).setType(Material.WATER, false);
            else world.getBlockAt(x, 64, z).setType(Material.FARMLAND, false);
        }
        for (int x = 30; x <= 84; x++) for (int z : List.of(-32, 28)) world.getBlockAt(x, 65, z).setType(Material.OAK_FENCE, false);
        building(70, 16, 13, 11, Material.BRICKS, Material.SPRUCE_PLANKS);
        workstations(74, 20, List.of(Material.COMPOSTER, Material.SMOKER, Material.BARREL));
    }

    private void ranch() {
        fill(-34, 64, 28, 30, 64, 86, Material.GRASS_BLOCK);
        for (int x = -32; x <= 28; x++) for (int z : List.of(30, 84)) world.getBlockAt(x, 65, z).setType(Material.OAK_FENCE, false);
        for (int z = 30; z <= 84; z++) for (int x : List.of(-32, 28)) world.getBlockAt(x, 65, z).setType(Material.OAK_FENCE, false);
        building(-26, 44, 15, 17, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS);
        workstations(-20, 50, List.of(Material.LOOM, Material.CAULDRON, Material.BARREL));
        for (int x = -3; x <= 7; x += 5) for (int z = 61; z <= 76; z += 5) {
            fill(x, 64, z, x, 66, z, Material.OAK_LOG); world.getBlockAt(x, 67, z).setType(Material.BEE_NEST, false);
        }
    }

    private void mine() {
        fill(12, 64, -86, 76, 64, -28, Material.STONE);
        for (int x = 14; x <= 74; x++) for (int z = -84; z <= -30; z++) if (Math.floorMod(x * 13 + z * 7, 19) == 0)
            world.getBlockAt(x, 65, z).setType(Material.COBBLED_DEEPSLATE, false);
        fill(14, 65, -84, 74, 72, -84, Material.DEEPSLATE_BRICKS);
        fill(14, 65, -30, 74, 72, -30, Material.DEEPSLATE_BRICKS);
        building(18, -46, 15, 13, Material.DEEPSLATE_BRICKS, Material.POLISHED_DEEPSLATE);
        workstations(22, -42, List.of(Material.SMITHING_TABLE, Material.ANVIL, Material.GRINDSTONE));
    }

    private void river() {
        fill(34, 61, 24, 86, 64, 86, Material.SAND);
        for (int x = 38; x <= 82; x++) for (int z = 28; z <= 82; z++) {
            int curve = 53 + (int)Math.round(10 * Math.sin(z / 9.0));
            if (Math.abs(x - curve) <= 8) {
                world.getBlockAt(x, 62, z).setType(Material.GRAVEL, false);
                world.getBlockAt(x, 63, z).setType(Material.WATER, false);
                world.getBlockAt(x, 64, z).setType(Material.WATER, false);
            }
        }
        building(70, 56, 13, 13, Material.PRISMARINE_BRICKS, Material.DARK_OAK_PLANKS);
        workstations(74, 60, List.of(Material.BARREL, Material.CAMPFIRE, Material.CRAFTING_TABLE));
    }

    private void boundary() {
        for (int x = -LIMIT; x <= LIMIT; x++) for (int y = 65; y <= 69 + Math.floorMod(x, 3); y++) {
            world.getBlockAt(x, y, -LIMIT).setType(Material.STONE, false);
            world.getBlockAt(x, y, LIMIT).setType(Material.MOSSY_COBBLESTONE, false);
        }
        for (int z = -LIMIT; z <= LIMIT; z++) for (int y = 65; y <= 69 + Math.floorMod(z, 3); y++) {
            world.getBlockAt(-LIMIT, y, z).setType(Material.STONE, false);
            world.getBlockAt(LIMIT, y, z).setType(Material.MOSSY_COBBLESTONE, false);
        }
    }

    private void road(int from, int to, int fixed, boolean xAxis) {
        for (int value = from; value <= to; value++) for (int side = -2; side <= 2; side++)
            world.getBlockAt(xAxis ? value : fixed + side, 64, xAxis ? fixed + side : value).setType(Material.DIRT_PATH, false);
    }

    private void tree(int x, int z, int extra) {
        fill(x, 65, z, x, 69 + extra, z, Material.OAK_LOG);
        fill(x - 2, 68 + extra, z - 2, x + 2, 70 + extra, z + 2, Material.OAK_LEAVES);
        world.getBlockAt(x, 71 + extra, z).setType(Material.OAK_LEAVES, false);
    }

    private void pond(int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) if (dx * dx + dz * dz <= radius * radius) {
            world.getBlockAt(x + dx, 63, z + dz).setType(Material.CLAY, false);
            world.getBlockAt(x + dx, 64, z + dz).setType(Material.WATER, false);
        }
    }

    private void building(int x, int z, int width, int depth, Material wall, Material roof) {
        fill(x, 64, z, x + width, 64, z + depth, Material.SMOOTH_STONE);
        for (int y = 65; y <= 70; y++) for (int dx = 0; dx <= width; dx++) for (int dz = 0; dz <= depth; dz++)
            if (dx == 0 || dx == width || dz == 0 || dz == depth) world.getBlockAt(x + dx, y, z + dz).setType(wall, false);
        fill(x + 1, 71, z + 1, x + width - 1, 71, z + depth - 1, roof);
        fill(x + width / 2 - 1, 65, z, x + width / 2 + 1, 67, z, Material.AIR);
        for (int dx = 2; dx < width; dx += 4) world.getBlockAt(x + dx, 67, z + depth).setType(Material.GLASS_PANE, false);
    }

    private void workstations(int x, int z, List<Material> stations) {
        for (int i = 0; i < stations.size(); i++) world.getBlockAt(x + i * 2, 65, z).setType(stations.get(i), false);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) world.getBlockAt(x, y, z).setType(material, false);
    }

    private void protect() {
        var regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regions == null) throw new IllegalStateException("자원 월드 WorldGuard를 열 수 없습니다.");
        GlobalProtectedRegion global = regions.getRegion("__global__") instanceof GlobalProtectedRegion found ? found : new GlobalProtectedRegion("__global__");
        global.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        global.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        global.setFlag(Flags.USE, StateFlag.State.ALLOW);
        global.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        global.setFlag(Flags.CHEST_ACCESS, StateFlag.State.ALLOW);
        for (String name : List.of("sit", "playersit", "pose", "crawl")) {
            Object flag = WorldGuard.getInstance().getFlagRegistry().get(name);
            if (flag instanceof StateFlag state) global.setFlag(state, StateFlag.State.DENY);
        }
        regions.addRegion(global);
        try { regions.save(); } catch (Exception error) { throw new IllegalStateException("자원 월드 보호 저장 실패", error); }
    }

    private void spawnNpcs() {
        if (!CitizensAPI.hasImplementation()) throw new IllegalStateException("Citizens가 없어 채집소 NPC를 만들 수 없습니다.");
        ensureNpc("resources", "채집소 관리인", -3.5, 65, 0.5);
        ensureNpc("lobby", "광장행 마부", 0.5, 65, 5.5);
        CitizensAPI.getNPCRegistry().saveToStore();
    }

    private void ensureNpc(String role, String name, double x, double y, double z) {
        ArrayList<NPC> found = new ArrayList<>();
        CitizensAPI.getNPCRegistry().forEach(npc -> { if (role.equals(npc.data().get(NPC_KEY, ""))) found.add(npc); });
        NPC npc = found.isEmpty() ? CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, name) : found.getFirst();
        found.stream().skip(1).forEach(NPC::destroy);
        npc.data().setPersistent(NPC_KEY, role); npc.setProtected(true);
        Location target = new Location(world, x, y, z);
        if (npc.isSpawned()) npc.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        else if (!npc.spawn(target)) throw new IllegalStateException(name + " Citizens NPC 생성 실패");
    }

    private static void validateLayout() {
        Set<String> ids = new HashSet<>(), positions = new HashSet<>();
        for (Node node : NODES) {
            if (!ids.add(node.id()) || !positions.add(node.x() + ":" + node.y() + ":" + node.z()))
                throw new IllegalStateException("자원 노드 ID 또는 위치가 중복됩니다.");
            if (Math.abs(node.x()) >= LIMIT || Math.abs(node.z()) >= LIMIT) throw new IllegalStateException("자원 노드가 월드 경계를 벗어났습니다: " + node.id());
        }
    }

    private record Area(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    }

    private record Node(String id, String name, int x, int y, int z, Material block, Material reward, Skill skill, String toolId) {
        boolean matches(int bx, int by, int bz) { return x == bx && y == by && z == bz; }
    }
}
