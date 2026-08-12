package kr.hyuni.marketplay;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

final class TutorialManager {
    static final String WORLD = "mp_tutorial";
    static final int FLOOR_Y = 64;
    private static final String DIALOGUE = "시장놀이_첫걸음";
    private static final int MIN = -18;
    private static final int MAX = 18;
    private static final int GUIDE_X = 0;
    private static final int GUIDE_Y = 65;
    private static final int GUIDE_Z = -7;

    private final MarketPlayPlugin plugin;
    private final NamespacedKey sampleKey;
    private final NamespacedKey displayKey;
    private World world;

    TutorialManager(MarketPlayPlugin plugin) {
        this.plugin = plugin;
        sampleKey = new NamespacedKey(plugin, "tutorial_sample");
        displayKey = new NamespacedKey(plugin, "tutorial_display");
    }

    void ensure() {
        boolean existed = Files.exists(plugin.getServer().getWorldContainer().toPath().resolve(WORLD).resolve("level.dat"));
        world = Bukkit.getWorld(WORLD);
        if (world == null) {
            world = Bukkit.createWorld(new WorldCreator(WORLD).type(WorldType.FLAT).generateStructures(false)
                    .generatorSettings("{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}"));
        }
        if (world == null) throw new IllegalStateException("튜토리얼 월드를 만들 수 없습니다.");
        Block marker = world.getBlockAt(0, FLOOR_Y - 2, 0);
        if (marker.getType() != Material.LODESTONE) {
            if (existed) throw new IllegalStateException("기존 튜토리얼 월드에 설치 표식이 없어 덮어쓰지 않습니다.");
            build();
            plugin.getLogger().info("분리된 시장놀이 튜토리얼 공간을 설치했습니다.");
        }
        protect();
        world.setSpawnLocation(spawn());
        updateDisplays();
    }

    void recover(Player player, PlayerProfile profile) {
        int step = profile.tutorialStep();
        if (step == TutorialProgress.NOT_STARTED || TutorialProgress.shouldRestartLegacy(step)) {
            begin(player, profile);
            return;
        }
        if (!TutorialProgress.active(step)) {
            if (WORLD.equals(player.getWorld().getName())) plugin.teleportLobby(player);
            return;
        }
        teleportHere(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> showCurrentStep(player, profile), 8L);
    }

    boolean handleGuide(Player player, Block block) {
        if (!WORLD.equals(block.getWorld().getName()) || block.getX() != GUIDE_X || block.getY() != GUIDE_Y || block.getZ() != GUIDE_Z) return false;
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null) return true;
        if (profile.tutorialStep() == TutorialProgress.DIALOGUE) replayDialogue(player);
        else player.sendMessage(Component.text("안내 대화는 이미 확인했습니다. 현재 화면의 튜토리얼 목표를 진행하세요.", NamedTextColor.YELLOW));
        return true;
    }

    void onDialogueComplete(Player player) {
        if (!advance(player, TutorialProgress.Action.DIALOGUE_COMPLETE)) return;
        display(player, "튜토리얼 2/4 · GUI", "Shift+F를 눌러 시장놀이 통합 메뉴를 여세요");
        player.sendMessage(Component.text("[GUI] Shift+F는 어디서든 주요 기능을 여는 빠른 메뉴입니다. 시장·도구함·게시판·주택·상태·여행 메뉴를 여기서 찾을 수 있습니다.", NamedTextColor.AQUA));
    }

    void onMenuOpened(Player player) {
        if (!advance(player, TutorialProgress.Action.MENU_OPENED)) return;
        display(player, "튜토리얼 3/4 · 시장", "GUI에서 '생활도구 시장'을 클릭하세요");
        player.sendMessage(Component.text("[GUI] 메뉴 아이콘을 클릭하면 해당 기능으로 이동합니다. 먼저 생활도구 시장을 직접 열어 보세요.", NamedTextColor.AQUA));
    }

    void onMarketOpened(Player player) {
        if (!advance(player, TutorialProgress.Action.MARKET_OPENED)) return;
        ensureSample(player);
        display(player, "튜토리얼 4/4 · 판매", "튜토리얼 사과를 주 손에 들고 GUI의 호퍼를 클릭하세요");
        player.sendMessage(Component.text("[판매] 시장 가격은 매일 변합니다. 판매할 시장놀이 자원을 주 손에 든 뒤 '손에 든 자원 판매' 호퍼를 누르면 실제 거래가 처리됩니다.", NamedTextColor.GREEN));
    }

    boolean isSample(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(sampleKey, PersistentDataType.BYTE);
    }

    void onSampleSold(Player player) {
        if (!advance(player, TutorialProgress.Action.SAMPLE_SOLD)) return;
        display(player, "튜토리얼 완료", "대화창 · GUI · 실제 판매를 모두 실습했습니다");
        player.sendMessage(Component.text("튜토리얼을 완료했습니다. 판매 대금은 그대로 보유합니다.", NamedTextColor.GREEN));
        player.sendMessage(Component.text("Shift+F: 통합 GUI · 시장: 도구 구매/판매 · 도구함: 보유 생활도구 · 게시판: 시세/주문/글 · 내 상태: 돈/내공/활력/숙련도", NamedTextColor.AQUA));
        player.sendMessage(Component.text("로비 남쪽 끝은 채집소, 북쪽 끝은 탐험지, 동쪽 끝은 던전·무한 탑·후반 지역입니다. 주택·그림·레스토랑·상단도 GUI와 안내 NPC에서 시작할 수 있습니다.", NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.teleportLobby(player);
        }, 30L);
    }

    private void begin(Player player, PlayerProfile profile) {
        profile.setTutorialStep(TutorialProgress.DIALOGUE);
        plugin.saveProfile(profile);
        teleportHere(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            display(player, "튜토리얼 1/4 · 대화창", "안내 대화를 끝까지 읽고 다음 단계로 진행하세요");
            replayDialogue(player);
        }, 10L);
    }

    private void showCurrentStep(Player player, PlayerProfile profile) {
        switch (profile.tutorialStep()) {
            case TutorialProgress.DIALOGUE -> {
                display(player, "튜토리얼 1/4 · 대화창", "앞의 안내대를 우클릭하거나 자동 대화를 끝까지 읽으세요");
                replayDialogue(player);
            }
            case TutorialProgress.OPEN_MENU -> display(player, "튜토리얼 2/4 · GUI", "Shift+F를 눌러 시장놀이 통합 메뉴를 여세요");
            case TutorialProgress.OPEN_MARKET -> display(player, "튜토리얼 3/4 · 시장", "GUI에서 '생활도구 시장'을 클릭하세요");
            case TutorialProgress.SELL_SAMPLE -> {
                ensureSample(player);
                display(player, "튜토리얼 4/4 · 판매", "튜토리얼 사과를 주 손에 들고 GUI의 호퍼를 클릭하세요");
            }
            default -> { }
        }
    }

    private boolean advance(Player player, TutorialProgress.Action action) {
        PlayerProfile profile = plugin.profile(player.getUniqueId());
        if (profile == null) return false;
        int current = profile.tutorialStep();
        int next = TutorialProgress.advance(current, action);
        if (next == current) return false;
        profile.setTutorialStep(next);
        plugin.saveProfile(profile);
        return true;
    }

    private void replayDialogue(Player player) {
        player.performCommand("rpgmaker play " + DIALOGUE);
    }

    private void ensureSample(Player player) {
        for (ItemStack item : player.getInventory().getContents()) if (isSample(item)) return;
        ItemStack sample = new ItemStack(Material.APPLE);
        plugin.tagTutorialItem(sample, "apple");
        sample.editMeta(meta -> {
            meta.displayName(Component.text("튜토리얼 사과 ★", NamedTextColor.GREEN));
            meta.lore(List.of(Component.text("판매 실습 전용 · 주 손에 들고 판매하세요", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(sampleKey, PersistentDataType.BYTE, (byte) 1);
        });
        if (!player.getInventory().addItem(sample).isEmpty()) {
            player.sendMessage(Component.text("인벤토리를 한 칸 비운 뒤 안내대를 우클릭하면 판매 실습 아이템을 다시 받을 수 있습니다.", NamedTextColor.RED));
        }
    }

    private void teleportHere(Player player) {
        if (world == null) return;
        player.teleportAsync(spawn());
    }

    private Location spawn() {
        return new Location(world, 0.5, FLOOR_Y + 1, 8.5, 180, 0);
    }

    private void display(Player player, String title, String subtitle) {
        player.showTitle(Title.title(Component.text(title, NamedTextColor.GOLD), Component.text(subtitle, NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(5), Duration.ofMillis(500))));
    }

    private void build() {
        fill(MIN, FLOOR_Y, MIN, MAX, FLOOR_Y, MAX, Material.SMOOTH_STONE);
        fill(MIN, FLOOR_Y + 1, MIN, MAX, FLOOR_Y + 5, MIN, Material.STONE_BRICKS);
        fill(MIN, FLOOR_Y + 1, MAX, MAX, FLOOR_Y + 5, MAX, Material.STONE_BRICKS);
        fill(MIN, FLOOR_Y + 1, MIN, MIN, FLOOR_Y + 5, MAX, Material.STONE_BRICKS);
        fill(MAX, FLOOR_Y + 1, MIN, MAX, FLOOR_Y + 5, MAX, Material.STONE_BRICKS);
        fill(-2, FLOOR_Y, -12, 2, FLOOR_Y, 12, Material.POLISHED_ANDESITE);
        fill(-12, FLOOR_Y, -2, 12, FLOOR_Y, 2, Material.POLISHED_ANDESITE);

        world.getBlockAt(GUIDE_X, GUIDE_Y, GUIDE_Z).setType(Material.LECTERN, false);
        world.getBlockAt(-9, FLOOR_Y + 1, 0).setType(Material.CHEST, false);
        world.getBlockAt(9, FLOOR_Y + 1, 0).setType(Material.EMERALD_BLOCK, false);
        world.getBlockAt(0, FLOOR_Y + 1, -12).setType(Material.HOPPER, false);
        world.getBlockAt(0, FLOOR_Y - 2, 0).setType(Material.LODESTONE, false);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) world.getBlockAt(x, y, z).setType(material, false);
    }

    private void protect() {
        RegionManager regions = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regions == null) throw new IllegalStateException("튜토리얼 WorldGuard 보호 구역을 열 수 없습니다.");
        BlockVector3 min = BlockVector3.at(MIN, FLOOR_Y - 2, MIN);
        BlockVector3 max = BlockVector3.at(MAX, FLOOR_Y + 12, MAX);
        ProtectedRegion existing = regions.getRegion("marketplay_tutorial");
        if (existing != null && (!existing.getMinimumPoint().equals(min) || !existing.getMaximumPoint().equals(max)))
            throw new IllegalStateException("기존 튜토리얼 보호 구역 좌표가 예상과 다릅니다.");
        ProtectedRegion region = existing == null ? new ProtectedCuboidRegion("marketplay_tutorial", min, max) : existing;
        region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        region.setFlag(Flags.USE, StateFlag.State.ALLOW);
        region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
        if (existing == null) regions.addRegion(region);
        try { regions.save(); }
        catch (Exception error) { throw new IllegalStateException("튜토리얼 WorldGuard 보호 저장 실패", error); }
    }

    private void updateDisplays() {
        world.getNearbyEntities(new Location(world, 0, FLOOR_Y + 3, 0), 30, 15, 30).stream()
                .filter(TextDisplay.class::isInstance).map(TextDisplay.class::cast)
                .filter(display -> display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING))
                .forEach(TextDisplay::remove);
        text(new Location(world, 0.5, FLOOR_Y + 3.2, 9.5), "welcome", "시장놀이 연습장\n대화 → GUI → 시장 → 판매", NamedTextColor.GOLD);
        text(new Location(world, 0.5, FLOOR_Y + 3.0, -6.5), "dialogue", "① 안내대 우클릭 · 대화창", NamedTextColor.YELLOW);
        text(new Location(world, -8.5, FLOOR_Y + 3.0, 0.5), "menu", "② Shift+F · 통합 GUI", NamedTextColor.AQUA);
        text(new Location(world, 8.5, FLOOR_Y + 3.0, 0.5), "market", "③ 생활도구 시장 · 기능 선택", NamedTextColor.GREEN);
        text(new Location(world, 0.5, FLOOR_Y + 3.0, -11.5), "sell", "④ 주 손 아이템 · 실제 판매", NamedTextColor.GREEN);
    }

    private void text(Location location, String id, String value, NamedTextColor color) {
        world.spawn(location, TextDisplay.class, display -> {
            display.text(Component.text(value, color));
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setShadowed(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, id);
        });
    }
}
