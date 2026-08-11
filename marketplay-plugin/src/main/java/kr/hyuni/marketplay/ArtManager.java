package kr.hyuni.marketplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class ArtManager implements Listener {
    private static final Component EDITOR_TITLE = Component.text("작품 캔버스", NamedTextColor.LIGHT_PURPLE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final Material[] PALETTE_ITEMS = {Material.BARRIER, Material.BLACK_DYE, Material.RED_DYE, Material.ORANGE_DYE, Material.YELLOW_DYE, Material.LIME_DYE, Material.CYAN_DYE, Material.BLUE_DYE, Material.PURPLE_DYE};
    private static final java.awt.Color[] PALETTE = {
            new java.awt.Color(236, 220, 180), java.awt.Color.BLACK, new java.awt.Color(190, 40, 40), new java.awt.Color(230, 120, 30),
            new java.awt.Color(245, 210, 60), new java.awt.Color(80, 190, 70), new java.awt.Color(45, 180, 190), new java.awt.Color(55, 90, 200), new java.awt.Color(135, 60, 180)
    };
    private static final Material[] CELL_ITEMS = {Material.WHITE_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE,
            Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE};

    private final MarketPlayPlugin plugin;
    private final ArtStore store;
    private final HousingStore housing;
    private final NamespacedKey artKey;
    private final NamespacedKey galleryKey;
    private final Map<String, ArtStore.Artwork> artworks = new ConcurrentHashMap<>();
    private final Map<UUID, Editor> editors = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> saves = new ConcurrentHashMap<>();
    private final Map<Integer, ItemFrame> gallery = new ConcurrentHashMap<>();

    ArtManager(MarketPlayPlugin plugin, ArtStore store, HousingStore housing) {
        this.plugin = plugin;
        this.store = store;
        this.housing = housing;
        artKey = new NamespacedKey(plugin, "artwork_id");
        galleryKey = new NamespacedKey(plugin, "art_gallery_slot");
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        store.all().thenCombine(store.exhibits(), List::of).whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.getLogger().severe("작품 데이터 로드 실패: " + error.getMessage()); return; }
            @SuppressWarnings("unchecked") List<ArtStore.Artwork> all = (List<ArtStore.Artwork>) loaded.get(0);
            @SuppressWarnings("unchecked") List<ArtStore.Exhibit> exhibits = (List<ArtStore.Exhibit>) loaded.get(1);
            all.forEach(this::cache);
            buildGallery(exhibits);
        }));
    }

    boolean command(Player player, String[] args) {
        if (args.length < 2) return help(player);
        return switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "new" -> create(player, args);
            case "edit" -> edit(player, args);
            case "finish" -> finish(player, args);
            case "get" -> give(player, args);
            case "list" -> list(player);
            case "sell" -> sell(player, args);
            case "buy" -> buy(player, args);
            case "gift" -> gift(player, args);
            case "plaza" -> plaza(player, args);
            default -> help(player);
        };
    }

    private boolean create(Player player, String[] args) {
        String title = args.length < 3 ? "" : String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        if (title.isEmpty() || title.length() > 48) return message(player, "제목은 1~48자로 입력하세요.", false);
        MapView view = Bukkit.createMap(Bukkit.getWorlds().getFirst());
        store.create(player.getUniqueId(), player.getName(), title, view.getId()).whenComplete((artwork, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { message(player, "캔버스를 만들지 못했습니다.", false); return; }
            cache(artwork);
            giveItem(player, item(artwork, null));
            open(player, artwork);
        }));
        return true;
    }

    private boolean edit(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 3 ? null : resolve(args[2]);
        if (!ownedDraft(artwork, player)) return message(player, "수정 가능한 내 초안을 찾지 못했습니다.", false);
        open(player, artwork);
        return true;
    }

    private boolean finish(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 3 ? null : resolve(args[2]);
        if (!ownedDraft(artwork, player)) return message(player, "완성할 내 초안을 찾지 못했습니다.", false);
        player.closeInventory();
        saves.getOrDefault(artwork.id(), CompletableFuture.completedFuture(null)).thenCompose(ignored -> store.publish(artwork.id(), player.getUniqueId()))
                .whenComplete((published, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) message(player, "작품 완성에 실패했습니다.", false);
                    else { cache(published); message(player, "작품을 완성했습니다: " + published.title(), true); }
                }));
        return true;
    }

    private boolean give(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 3 ? null : resolve(args[2]);
        if (artwork == null || !artwork.owner().equals(player.getUniqueId())) return message(player, "소유한 작품을 찾지 못했습니다.", false);
        giveItem(player, item(artwork, null));
        return true;
    }

    private boolean list(Player player) {
        List<ArtStore.Artwork> owned = artworks.values().stream().filter(a -> a.owner().equals(player.getUniqueId())).sorted(java.util.Comparator.comparing(ArtStore.Artwork::createdAt)).toList();
        if (owned.isEmpty()) return message(player, "보유 작품이 없습니다.", false);
        player.sendMessage(Component.text("내 작품", NamedTextColor.LIGHT_PURPLE));
        owned.forEach(a -> player.sendMessage(Component.text(a.id().substring(0, 8) + " · " + a.title() + " · " + a.state() + (a.price() == null ? "" : " · " + a.price() + "원"), NamedTextColor.WHITE)));
        return true;
    }

    private boolean sell(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 4 ? null : resolve(args[2]);
        long price;
        try { price = Long.parseLong(args[3]); } catch (Exception error) { return message(player, "사용법: /mp art sell <작품ID> <가격>", false); }
        if (artwork == null || !artwork.owner().equals(player.getUniqueId())) return message(player, "소유한 작품을 찾지 못했습니다.", false);
        store.listForSale(artwork.id(), player.getUniqueId(), price).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) message(player, "판매 등록에 실패했습니다.", false);
            else { cache(copy(artwork, artwork.owner(), price)); message(player, price + "원에 판매 등록했습니다.", true); }
        }));
        return true;
    }

    private boolean buy(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 3 ? null : resolve(args[2]);
        if (artwork == null || artwork.price() == null) return message(player, "판매 중인 작품을 찾지 못했습니다.", false);
        String grantId = "art-buy-" + UUID.randomUUID();
        ItemStack delivered = item(copy(artwork, player.getUniqueId(), null), grantId);
        store.buy(artwork.id(), player.getUniqueId(), grantId, delivered.serializeAsBytes()).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { message(player, "잔액이 부족하거나 작품이 이미 판매되었습니다.", false); return; }
            cache(result.artwork());
            updateMoney(player.getUniqueId(), result.buyerBalance());
            updateMoney(result.seller(), result.sellerBalance());
            plugin.deliverPendingGrants(player);
            message(player, "작품을 구매했습니다: " + result.artwork().title(), true);
        }));
        return true;
    }

    private boolean gift(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 4 ? null : resolve(args[2]);
        if (artwork == null || !artwork.owner().equals(player.getUniqueId())) return message(player, "소유한 작품을 찾지 못했습니다.", false);
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[3]);
        if (target == null) return message(player, "접속 기록이 있는 플레이어를 찾지 못했습니다.", false);
        String grantId = "art-gift-" + UUID.randomUUID();
        ItemStack delivered = item(copy(artwork, target.getUniqueId(), null), grantId);
        store.gift(artwork.id(), player.getUniqueId(), player.getName(), target.getUniqueId(), grantId, delivered.serializeAsBytes())
                .whenComplete((moved, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) message(player, "작품 선물에 실패했습니다.", false);
                    else { cache(moved); Player online = target.getPlayer(); if (online != null) plugin.deliverPendingGrants(online); message(player, target.getName() + "에게 작품을 보냈습니다.", true); }
                }));
        return true;
    }

    private boolean plaza(Player player, String[] args) {
        ArtStore.Artwork artwork = args.length < 3 ? null : resolve(args[2]);
        if (artwork == null || !artwork.owner().equals(player.getUniqueId())) return message(player, "전시할 내 작품을 찾지 못했습니다.", false);
        store.exhibit(artwork.id(), player.getUniqueId()).whenComplete((slot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) message(player, "광장 전시대가 가득 찼거나 전시할 수 없습니다.", false);
            else { setGallery(slot, artwork); message(player, "광장 전시대 " + (slot + 1) + "번에 전시했습니다.", true); }
        }));
        return true;
    }

    private void open(Player player, ArtStore.Artwork artwork) {
        Inventory inventory = Bukkit.createInventory(null, 54, EDITOR_TITLE);
        Editor editor = new Editor(artwork, artwork.pixels().clone(), 1, inventory);
        editors.put(player.getUniqueId(), editor);
        redraw(editor);
        player.openInventory(inventory);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Editor editor = editors.get(player.getUniqueId());
        if (editor == null || event.getView().getTopInventory() != editor.inventory) {
            if (event.getView().getTopInventory().getType() == InventoryType.CARTOGRAPHY && containsArt(event.getView().getTopInventory())) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (slot >= 45) { editor.color = slot - 45; redraw(editor); return; }
        int color = event.isShiftClick() ? 0 : editor.color;
        paint(editor, slot, color, event.isRightClick());
        redraw(editor);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Editor editor = editors.remove(player.getUniqueId());
        if (editor == null || event.getInventory() != editor.inventory) return;
        CompletableFuture<Void> save = store.saveDraft(editor.artwork.id(), player.getUniqueId(), editor.pixels);
        saves.put(editor.artwork.id(), save);
        save.whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            saves.remove(editor.artwork.id(), save);
            if (error != null) plugin.getLogger().severe("작품 초안 저장 실패: " + error.getMessage());
            else cache(new ArtStore.Artwork(editor.artwork.id(), editor.artwork.author(), editor.artwork.authorName(), editor.artwork.title(), editor.artwork.createdAt(), editor.artwork.width(), editor.artwork.height(), editor.pixels.clone(), editor.artwork.mapId(), editor.artwork.state(), editor.artwork.owner(), editor.artwork.price()));
        }));
    }

    @EventHandler public void onCraft(PrepareItemCraftEvent event) {
        if (Arrays.stream(event.getInventory().getMatrix()).anyMatch(this::isArt)) event.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true) public void onFrame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        Integer gallerySlot = frame.getPersistentDataContainer().get(galleryKey, PersistentDataType.INTEGER);
        if (gallerySlot != null) { event.setCancelled(true); return; }
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        String id = artId(held);
        if (id == null) return;
        ArtStore.Artwork artwork = artworks.get(id);
        if (artwork == null || !artwork.state().equals("PUBLISHED") || !artwork.owner().equals(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            message(event.getPlayer(), "현재 소유한 완성 작품만 전시할 수 있습니다.", false);
        }
    }

    private void buildGallery(List<ArtStore.Exhibit> exhibits) {
        World world = Bukkit.getWorlds().getFirst();
        world.getEntitiesByClass(ItemFrame.class).stream().filter(frame -> frame.getPersistentDataContainer().has(galleryKey, PersistentDataType.INTEGER)).forEach(ItemFrame::remove);
        for (int x = -4; x <= 3; x++) world.getBlockAt(x, HubBuilder.FLOOR_Y + 2, -24).setType(Material.SMOOTH_QUARTZ);
        for (int slot = 0; slot < 8; slot++) {
            int x = -4 + slot;
            ItemFrame frame = world.spawn(new Location(world, x, HubBuilder.FLOOR_Y + 2, -23), ItemFrame.class);
            frame.setFacingDirection(BlockFace.SOUTH, true);
            frame.setFixed(true);
            frame.setVisible(true);
            frame.getPersistentDataContainer().set(galleryKey, PersistentDataType.INTEGER, slot);
            gallery.put(slot, frame);
        }
        exhibits.forEach(exhibit -> { ArtStore.Artwork artwork = artworks.get(exhibit.artworkId()); if (artwork != null) setGallery(exhibit.slot(), artwork); });
    }

    private void setGallery(int slot, ArtStore.Artwork artwork) {
        ItemFrame frame = gallery.get(slot);
        if (frame != null) frame.setItem(item(artwork, null), false);
    }

    private void cache(ArtStore.Artwork artwork) {
        artworks.put(artwork.id(), artwork);
        MapView view = Bukkit.getMap(artwork.mapId());
        if (view == null) {
            int replacement = Bukkit.createMap(Bukkit.getWorlds().getFirst()).getId();
            store.remap(artwork.id(), replacement).whenComplete((repaired, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) plugin.getLogger().severe("작품 지도 복구 실패: " + error.getMessage());
                else cache(repaired);
            }));
            return;
        }
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.addRenderer(new Renderer(artwork.id()));
        gallery.values().stream().filter(frame -> artwork.id().equals(artId(frame.getItem()))).forEach(frame -> frame.setItem(item(artwork, null), false));
    }

    private ItemStack item(ArtStore.Artwork artwork, String grantId) {
        MapView view = Bukkit.getMap(artwork.mapId());
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        item.editMeta(meta -> {
            if (meta instanceof MapMeta map) map.setMapView(view);
            meta.displayName(Component.text(artwork.title(), NamedTextColor.LIGHT_PURPLE));
            meta.lore(List.of(Component.text("작가 " + artwork.authorName(), NamedTextColor.GRAY), Component.text(DATE.format(artwork.createdAt()) + " · " + artwork.width() + "×" + artwork.height(), NamedTextColor.DARK_GRAY)));
            meta.getPersistentDataContainer().set(artKey, PersistentDataType.STRING, artwork.id());
            if (grantId != null) meta.getPersistentDataContainer().set(plugin.grantKey(), PersistentDataType.STRING, grantId);
        });
        return item;
    }

    private void paint(Editor editor, int slot, int color, boolean wide) {
        int x = slot % 9, y = slot / 9;
        for (int dy = wide ? -1 : 0; dy <= (wide ? 1 : 0); dy++) for (int dx = wide ? -1 : 0; dx <= (wide ? 1 : 0); dx++) {
            int px = x + dx, py = y + dy;
            if (px >= 0 && px < 9 && py >= 0 && py < 5) editor.pixels[py * 9 + px] = (byte) color;
        }
    }

    private void redraw(Editor editor) {
        for (int slot = 0; slot < 45; slot++) editor.inventory.setItem(slot, named(CELL_ITEMS[Byte.toUnsignedInt(editor.pixels[slot])], " "));
        for (int color = 0; color < 9; color++) editor.inventory.setItem(45 + color, named(PALETTE_ITEMS[color], (editor.color == color ? "▶ " : "") + (color == 0 ? "지우개" : "색 " + color)));
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(name, NamedTextColor.WHITE)));
        return item;
    }

    private ArtStore.Artwork resolve(String token) {
        List<ArtStore.Artwork> matches = artworks.values().stream().filter(a -> a.id().startsWith(token)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean ownedDraft(ArtStore.Artwork artwork, Player player) { return artwork != null && artwork.owner().equals(player.getUniqueId()) && artwork.state().equals("DRAFT"); }
    private String artId(ItemStack item) { return item == null ? null : item.getPersistentDataContainer().get(artKey, PersistentDataType.STRING); }
    private boolean isArt(ItemStack item) { return artId(item) != null; }
    private boolean containsArt(Inventory inventory) { return Arrays.stream(inventory.getContents()).anyMatch(this::isArt); }
    private void giveItem(Player player, ItemStack item) { if (!player.getInventory().addItem(item).isEmpty()) message(player, "인벤토리를 비우고 /mp art get으로 다시 받으세요.", false); }

    private void updateMoney(UUID playerId, long balance) {
        PlayerProfile profile = plugin.profile(playerId);
        if (profile == null) return;
        synchronized (profile) { profile.setMoney(balance); }
        plugin.saveProfile(profile);
    }

    private ArtStore.Artwork copy(ArtStore.Artwork artwork, UUID owner, Long price) {
        return new ArtStore.Artwork(artwork.id(), artwork.author(), artwork.authorName(), artwork.title(), artwork.createdAt(), artwork.width(), artwork.height(), artwork.pixels(), artwork.mapId(), artwork.state(), owner, price);
    }

    private boolean help(Player player) {
        player.sendMessage(Component.text("/mp art new <제목> | edit/finish/get/sell/buy/gift/plaza <작품ID>", NamedTextColor.AQUA));
        return true;
    }

    private boolean message(Player player, String text, boolean success) { player.sendMessage(Component.text(text, success ? NamedTextColor.GREEN : NamedTextColor.RED)); return true; }

    private final class Renderer extends MapRenderer {
        private final String id;
        private int renderedHash = Integer.MIN_VALUE;
        private Renderer(String id) { super(false); this.id = id; }
        @Override public void render(MapView map, MapCanvas canvas, Player player) {
            ArtStore.Artwork artwork = artworks.get(id);
            if (artwork == null) return;
            byte[] pixels = artwork.pixels();
            int hash = Arrays.hashCode(pixels);
            if (hash == renderedHash) return;
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                int cellX = Math.min(8, x * 9 / 128), cellY = Math.min(4, y * 5 / 128);
                canvas.setPixelColor(x, y, PALETTE[Byte.toUnsignedInt(pixels[cellY * 9 + cellX])]);
            }
            renderedHash = hash;
        }
    }

    private static final class Editor {
        private final ArtStore.Artwork artwork;
        private final byte[] pixels;
        private int color;
        private final Inventory inventory;
        private Editor(ArtStore.Artwork artwork, byte[] pixels, int color, Inventory inventory) { this.artwork = artwork; this.pixels = pixels; this.color = color; this.inventory = inventory; }
    }
}
