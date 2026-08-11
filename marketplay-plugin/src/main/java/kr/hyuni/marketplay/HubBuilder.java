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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.util.List;

final class HubBuilder {
    static final int FLOOR_Y = 64;
    static final LocationKey MARKET = new LocationKey(0, 65, -17);
    static final LocationKey SELL = new LocationKey(3, 65, -17);
    static final List<Node> NODES = List.of(
            new Node("apple", "사과나무", new LocationKey(-18, 66, 0), Material.APPLE, Skill.FORAGING, "old_net"),
            new Node("oak_log", "참나무", new LocationKey(-20, 65, 3), Material.OAK_LOG, Skill.WOODCUTTING, "old_axe"),
            new Node("wheat", "밀밭", new LocationKey(18, 65, 0), Material.WHEAT, Skill.FARMING, "old_hoe"),
            new Node("wool", "목장", new LocationKey(-17, 65, 17), Material.WHITE_WOOL, Skill.FORAGING, "old_shears"),
            new Node("iron_ore", "초보 광맥", new LocationKey(-17, 65, -17), Material.RAW_IRON, Skill.MINING, "old_pickaxe")
    );

    private final MarketPlayPlugin plugin;
    private final NamespacedKey displayKey;

    HubBuilder(MarketPlayPlugin plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, "hub_display");
    }

    boolean ensure(World world) {
        Block marker = world.getBlockAt(0, FLOOR_Y - 2, 0);
        if (marker.getType() != Material.LODESTONE) {
            if (!isEmpty(world)) {
                plugin.getLogger().warning("중앙광장 위치에 기존 블록이 있어 자동 설치를 건너뜁니다.");
                return false;
            }
            paste(world);
            plugin.getLogger().info("FAWE schematic으로 시장놀이 중앙광장을 설치했습니다.");
        }
        protect(world);
        world.setSpawnLocation(new Location(world, 0.5, FLOOR_Y + 1, 10.5));
        updateDisplays(world);
        return true;
    }

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

    private void protect(World world) {
        RegionManager regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regions == null) throw new IllegalStateException("WorldGuard region manager를 열 수 없습니다.");
        ensureRegion(regions, "marketplay_plaza", BlockVector3.at(-24, FLOOR_Y - 2, -24), BlockVector3.at(24, FLOOR_Y + 16, 24));
        ensureRegion(regions, "marketplay_beginner_grove", BlockVector3.at(-23, FLOOR_Y, -7), BlockVector3.at(-12, FLOOR_Y + 12, 8));
        try { regions.save(); } catch (Exception error) { throw new IllegalStateException("WorldGuard 보호 저장 실패", error); }
    }

    private void ensureRegion(RegionManager regions, String id, BlockVector3 min, BlockVector3 max) {
        ProtectedRegion existing = regions.getRegion(id);
        if (existing != null && (!existing.getMinimumPoint().equals(min) || !existing.getMaximumPoint().equals(max)))
            throw new IllegalStateException(id + " 보호 구역 좌표가 예상과 다릅니다.");
        ProtectedRegion region = existing == null ? new ProtectedCuboidRegion(id, min, max) : existing;
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        if (existing == null) regions.addRegion(region);
    }

    void updateDisplays(World world) {
        world.getNearbyEntities(new Location(world, 0, FLOOR_Y + 4, 0), 40, 20, 40).stream()
                .filter(TextDisplay.class::isInstance).map(TextDisplay.class::cast)
                .filter(display -> display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING))
                .forEach(TextDisplay::remove);
        display(world, new Location(world, -5.5, FLOOR_Y + 3.2, -16.7), "market",
                Component.text(MarketText.render(plugin.prices()), NamedTextColor.GOLD));
        display(world, new Location(world, 12.5, FLOOR_Y + 3.0, 8.5), "bulletin",
                Component.text(String.join("\n", plugin.getConfig().getStringList("bulletin")), NamedTextColor.AQUA));
        display(world, new Location(world, 0.5, FLOOR_Y + 3.0, 10.5), "welcome",
                Component.text("시장놀이 중앙광장\n분수 앞에서 안내인을 만나세요", NamedTextColor.YELLOW));
    }

    private void display(World world, Location location, String id, Component text) {
        world.spawn(location, TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setShadowed(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, id);
        });
    }

    record LocationKey(int x, int y, int z) {
        boolean matches(Block block) { return block.getX() == x && block.getY() == y && block.getZ() == z; }
    }

    record Node(String id, String name, LocationKey location, Material reward, Skill skill, String toolId) {}
}
