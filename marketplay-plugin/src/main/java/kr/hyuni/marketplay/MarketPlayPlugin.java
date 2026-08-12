package kr.hyuni.marketplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class MarketPlayPlugin extends JavaPlugin implements Listener {
    private static final Component MARKET_TITLE = Component.text("생활도구 상점", NamedTextColor.GOLD);
    private static final Component TOOLBOX_TITLE = Component.text("생활도구함", NamedTextColor.AQUA);
    private static final Component HUB_TITLE = Component.text("시장놀이 안내", NamedTextColor.GOLD);
    private static final Component BOARD_TITLE = Component.text("시장 게시판", NamedTextColor.YELLOW);
    private static final Component HOUSING_TITLE = Component.text("주택 안내", NamedTextColor.GREEN);
    private static final Component STATUS_TITLE = Component.text("내 상태", NamedTextColor.GREEN);
    private static final Component HELP_TITLE = Component.text("시장놀이 도움말", NamedTextColor.AQUA);
    private ProfileStore profiles;
    private HousingStore housingStore;
    private HousingManager housing;
    private ArtStore artStore;
    private ArtManager art;
    private ExplorationManager exploration;
    private SocialEconomyManager socialEconomy;
    private EndgameManager endgame;
    private ResourceWorldManager resources;
    private RankTable ranks;
    private final Map<Material, Skill> activityBlocks = new EnumMap<>(Material.class);
    private NamespacedKey qualityKey;
    private NamespacedKey itemIdKey;
    private NamespacedKey itemSchemaKey;
    private NamespacedKey toolKey;
    private NamespacedKey grantKey;
    private NamespacedKey saleIntentKey;
    private NamespacedKey hubActionKey;
    private HubBuilder hub;
    private boolean hubReady;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nodeCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fishingDeadlines = new ConcurrentHashMap<>();
    private final Map<UUID, EquipmentSlot> validFishingCasts = new ConcurrentHashMap<>();
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final Map<String, Long> basePrices = new LinkedHashMap<>();
    private final Map<String, Long> prices = new LinkedHashMap<>();
    private MarketDay marketDay;
    private List<ProfileStore.BulletinPost> bulletinPosts = List.of();
    private double maximumVitality;
    private double activityCost;

    @Override public void onEnable() {
        saveDefaultConfig();
        qualityKey = new NamespacedKey(this, "quality");
        itemIdKey = new NamespacedKey(this, "item_id");
        itemSchemaKey = new NamespacedKey(this, "item_schema");
        toolKey = new NamespacedKey(this, "tool_id");
        grantKey = new NamespacedKey(this, "grant_id");
        saleIntentKey = new NamespacedKey(this, "sale_intent");
        hubActionKey = new NamespacedKey(this, "hub_action");
        reloadRuntimeConfig();
        try {
            var database = getDataFolder().toPath().resolve("marketplay.db");
            profiles = new ProfileStore(database, getConfig().getLong("starting-money", 1000), maximumVitality,
                    new ProfileStore.SocialBalance(getConfig().getInt("social-economy.guild-project.logs", 64), getConfig().getInt("social-economy.guild-project.iron", 32),
                            getConfig().getLong("social-economy.guild-project.money", 2000), getConfig().getLong("social-economy.restaurant.base-reward", 100), getConfig().getLong("social-economy.restaurant.quality-reward", 40)));
            housingStore = new HousingStore(database);
            artStore = new ArtStore(database);
        }
        catch (Exception error) {
            getLogger().severe("데이터베이스 시작 실패: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        hub = new HubBuilder(this);
        resources = new ResourceWorldManager(this);
        housing = new HousingManager(this, housingStore);
        housing.start();
        exploration = new ExplorationManager(this, profiles, housing);
        socialEconomy = new SocialEconomyManager(this, profiles);
        socialEconomy.start();
        endgame = new EndgameManager(this, profiles);
        endgame.start();
        art = new ArtManager(this, artStore, housingStore);
        refreshMarketDay();
        refreshBulletins();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers()) {
            housingStore.remember(player.getUniqueId(), player.getName());
            load(player);
        }
        long period = 20L * 60L;
        getServer().getScheduler().runTaskTimer(this, this::regenerateVitality, period, period);
        getServer().getScheduler().runTaskTimer(this, this::refreshMarketDay, period, period);
        getServer().getScheduler().runTaskTimer(this, this::refreshBulletins, period, period);
    }

    @EventHandler public void onCitizensEnable(CitizensEnableEvent event) {
        try {
            hubReady = hub.ensure();
            art.start();
            resources.start();
            exploration.start();
        } catch (RuntimeException error) {
            getLogger().severe("Citizens NPC 및 월드 시작 실패: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (profiles == null) return;
        try {
            if (housing != null) housing.stop();
            if (resources != null) resources.stop();
            if (exploration != null) exploration.stop();
            if (socialEconomy != null) socialEconomy.stop();
            if (endgame != null) endgame.stop();
            if (artStore != null) artStore.close();
            if (housingStore != null) housingStore.close();
            profiles.close();
        }
        catch (Exception error) { getLogger().severe("플레이어 데이터 저장 실패: " + error.getMessage()); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        housingStore.remember(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        load(event.getPlayer());
        if ("world".equals(event.getPlayer().getWorld().getName())) getServer().getScheduler().runTask(this, () -> hub.teleport(event.getPlayer()));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        busy.remove(event.getPlayer().getUniqueId());
        fishingDeadlines.remove(event.getPlayer().getUniqueId());
        validFishingCasts.remove(event.getPlayer().getUniqueId());
        profiles.unload(event.getPlayer().getUniqueId()).exceptionally(error -> {
            getLogger().severe("플레이어 데이터 저장 실패: " + error.getMessage());
            return null;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!hubReady || event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        PlayerProfile profile = profiles.get(event.getPlayer().getUniqueId());
        if (HubBuilder.TUTORIAL_TOOL.matches(event.getClickedBlock())) {
            event.setCancelled(true);
            if (profile != null && profile.tutorialStep() == 3) {
                profile.addTool("old_axe");
                profile.addTool("old_net");
                saveProfile(profile);
                advanceTutorial(event.getPlayer(), 3, 4, "튜토리얼 3/6", "도구 준비 완료 · 앞의 통나무를 우클릭");
            }
            return;
        }
        if (HubBuilder.TUTORIAL_LOG.matches(event.getClickedBlock())) {
            event.setCancelled(true);
            if (profile != null && profile.tutorialStep() == 4 && profile.hasTool("old_axe"))
                advanceTutorial(event.getPlayer(), 4, 5, "튜토리얼 4/6", "도구 사용 완료 · 앞의 열매를 채집하세요");
            return;
        }
        if (HubBuilder.TUTORIAL_CROP.matches(event.getClickedBlock())) {
            event.setCancelled(true);
            if (profile != null && profile.tutorialStep() == 5)
                harvest(event.getPlayer(), "tutorial:berry", "sweet_berries", "튜토리얼 열매", Material.SWEET_BERRIES, Skill.FORAGING, "old_net");
            return;
        }
        if (HubBuilder.MARKET.matches(event.getClickedBlock())) {
            event.setCancelled(true);
            openMarket(event.getPlayer());
            return;
        }
        if (HubBuilder.SELL.matches(event.getClickedBlock())) {
            event.setCancelled(true);
            sellHand(event.getPlayer());
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHubNpc(NPCRightClickEvent event) {
        if (event.getNPC().data().has("rpgmaker-guide-dialogue")) return;
        String role = event.getNPC().data().get("marketplay_hub_role", "");
        if (role.isBlank()) return;
        event.setCancelled(true);
        Player player = event.getClicker();
        switch (role) {
            case "market" -> openMarket(player);
            case "board" -> openBoardMenu(player);
            case "housing" -> openHousingMenu(player);
            case "travel", "lobby" -> showTravelGuide(player);
            case "adventure" -> showAdventureGuide(player);
            case "resources" -> openHubMenu(player, "resources");
            case "art" -> openHubMenu(player, "art");
            case "restaurant" -> openHubMenu(player, "restaurant");
            case "guild" -> openHubMenu(player, "guild");
            default -> openHubMenu(player, "hub");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (busy.contains(player.getUniqueId())) { event.setCancelled(true); return; }
        boolean market = event.getView().title().equals(MARKET_TITLE);
        boolean toolbox = event.getView().title().equals(TOOLBOX_TITLE);
        boolean hubMenu = event.getView().title().equals(HUB_TITLE)
                || event.getView().title().equals(BOARD_TITLE) || event.getView().title().equals(HOUSING_TITLE)
                || event.getView().title().equals(STATUS_TITLE) || event.getView().title().equals(HELP_TITLE);
        if (!market && !toolbox && !hubMenu) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        String toolId = clicked.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        String action = clicked.getPersistentDataContainer().get(hubActionKey, PersistentDataType.STRING);
        if (hubMenu && action != null) { player.closeInventory(); runHubAction(player, action); return; }
        if (toolbox && toolId != null) player.sendActionBar(Component.text(toolId.equals("old_rod") ? "낡은 낚싯대는 손에 들고 사용합니다." : "대상과 상호작용하면 도구가 자동 사용됩니다.", NamedTextColor.AQUA));
        else if (market && toolId != null) purchaseTool(player, toolId);
        else if (clicked.getType() == Material.HOPPER) { player.closeInventory(); sellHand(player); }
        else if (clicked.getType() == Material.CHEST) openToolbox(player);
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && busy.contains(player.getUniqueId())) { event.setCancelled(true); return; }
        if (event.getView().title().equals(MARKET_TITLE) || event.getView().title().equals(TOOLBOX_TITLE)
                || event.getView().title().equals(HUB_TITLE)
                || event.getView().title().equals(BOARD_TITLE) || event.getView().title().equals(HOUSING_TITLE)
                || event.getView().title().equals(STATUS_TITLE) || event.getView().title().equals(HELP_TITLE)) event.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (busy.contains(player.getUniqueId())) { event.setCancelled(true); return; }
        if (!player.isSneaking()) return;
        event.setCancelled(true);
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile != null && profile.tutorialStep() == 2) {
            openHelpMenu(player, 1);
            advanceTutorial(player, 2, 3, "튜토리얼 2/6", "GUI 확인 완료 · 도구 상자를 우클릭");
            return;
        }
        openHubMenu(player, "hub");
    }
    @EventHandler public void onHeld(PlayerItemHeldEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true) public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getWorld().getName().startsWith("mp_house_")) return;
        Skill skill = activityBlocks.get(event.getBlock().getType());
        if (skill != null) rewardActivity(event.getPlayer(), skill);
    }

    @EventHandler(ignoreCancelled = true) public void onMobDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player != null && event.getEntity() instanceof Monster && hub.isTutorialMonster(event.getEntity()))
            advanceTutorial(player, 6, 7, "튜토리얼 6/6", "사냥 완료 · 길 끝 출구로 이동하세요");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMapEdge(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (!event.hasChangedBlock() || to == null || busy.contains(event.getPlayer().getUniqueId())) return;
        PlayerProfile profile = profiles.get(event.getPlayer().getUniqueId());
        if (profile != null && profile.tutorialStep() == 7 && HubBuilder.WORLD.equals(to.getWorld().getName())
                && to.getBlockX() >= -66 && to.getBlockX() <= -62 && to.getBlockZ() >= 74) {
            advanceTutorial(event.getPlayer(), 7, 8, "튜토리얼 완료", "NPC·GUI·도구·채집·사냥을 익혔습니다");
            hub.teleport(event.getPlayer());
            return;
        }
        String destination = ProfileStore.mapEdgeDestination(to.getWorld().getName(), to.getBlockX(), to.getBlockZ());
        if (destination == null) return;
        switch (destination) {
            case "resources" -> resources.teleport(event.getPlayer());
            case "exploration" -> exploration.teleportEntrance(event.getPlayer());
            case "lobby" -> teleportLobby(event.getPlayer());
            default -> endgame.teleport(event.getPlayer(), destination);
        }
    }

    @EventHandler(ignoreCancelled = true) public void onBlockDrop(BlockDropItemEvent event) {
        if (event.getBlock().getWorld().getName().startsWith("mp_house_")) return;
        if (!activityBlocks.containsKey(event.getBlockState().getType())) return;
        event.getItems().forEach(item -> tag(item.getItemStack()));
    }

    @EventHandler(ignoreCancelled = true) public void onFish(PlayerFishEvent event) {
        if (exploration != null && exploration.onSeaFish(event)) return;
        UUID playerId = event.getPlayer().getUniqueId();
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            fishingDeadlines.remove(playerId);
            PlayerProfile profile = profiles.get(playerId);
            if (resources == null || !resources.isRiver(event.getPlayer().getLocation())) {
                event.setCancelled(true);
                validFishingCasts.remove(playerId);
                event.getPlayer().sendActionBar(Component.text("낚시는 강 지역에서만 할 수 있습니다.", NamedTextColor.RED));
                return;
            }
            if (profile == null || !profile.hasTool("old_rod") || !"old_rod".equals(toolInHand(event.getPlayer(), event.getHand()))) {
                event.setCancelled(true);
                validFishingCasts.remove(playerId);
                event.getPlayer().sendActionBar(Component.text("전용 도구함의 낡은 낚싯대를 손에 들어야 합니다.", NamedTextColor.RED));
                return;
            }
            validFishingCasts.put(playerId, event.getHand());
            return;
        }
        if (event.getState() == PlayerFishEvent.State.BITE) {
            if (!validFishingCasts.containsKey(playerId)) return;
            long window = getConfig().getLong("fishing-reel-window-millis", 1500);
            fishingDeadlines.put(playerId, System.currentTimeMillis() + window);
            event.getPlayer().sendActionBar(Component.text("입질! 지금 낚싯대를 감으세요.", NamedTextColor.AQUA));
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY || event.getState() == PlayerFishEvent.State.FAILED_ATTEMPT || event.getState() == PlayerFishEvent.State.IN_GROUND || event.getState() == PlayerFishEvent.State.REEL_IN) {
                validFishingCasts.remove(playerId);
                fishingDeadlines.remove(playerId);
            }
            return;
        }
        Long deadline = fishingDeadlines.remove(playerId);
        PlayerProfile profile = profiles.get(playerId);
        EquipmentSlot hand = validFishingCasts.remove(playerId);
        if (hand == null || profile == null || resources == null || !resources.isRiver(event.getPlayer().getLocation()) || !profile.hasTool("old_rod") || !"old_rod".equals(toolInHand(event.getPlayer(), hand)) || !FishingTiming.caught(deadline, System.currentTimeMillis())) {
            event.setCancelled(true);
            if (event.getCaught() != null) event.getCaught().remove();
            event.getPlayer().sendActionBar(Component.text("놓쳤습니다. 입질 직후 낚싯대를 감으세요.", NamedTextColor.RED));
            return;
        }
        if (event.getCaught() instanceof org.bukkit.entity.Item item) tag(item.getItemStack());
        rewardActivity(event.getPlayer(), Skill.FISHING);
    }

    private void rewardActivity(Player player, Skill skill) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) return;
        synchronized (profile) {
            if (!profile.spendVitality(activityCost)) {
                player.sendActionBar(Component.text("활력이 부족합니다. 휴식하거나 식사하세요.", NamedTextColor.RED));
                return;
            }
            profile.addExperience(skill, 1);
            profile.addInnerPower(1);
            profiles.save(profile).exceptionally(error -> {
                getLogger().severe("활동 저장 실패: " + error.getMessage());
                getServer().getScheduler().runTask(this, () -> player.sendActionBar(Component.text("활동 저장에 실패했습니다.", NamedTextColor.RED)));
                return null;
            });
        }
        player.sendActionBar(Component.text(skill.displayName() + " 숙련도 +1 · 내공 +1", NamedTextColor.GREEN));
        if (profile.tutorialStep() == 5) {
            advanceTutorial(player, 5, 6, "튜토리얼 5/6", "채집 완료 · 앞의 몬스터를 처치하세요");
            hub.spawnTutorialMonster();
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("marketplay.admin")) return true;
            reloadConfig();
            reloadRuntimeConfig();
            sender.sendMessage(Component.text("MarketPlay 설정을 다시 읽었습니다.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) return admin(sender, args);
        if (!(sender instanceof Player player)) return true;
        if (args.length > 1 && args[0].equalsIgnoreCase("dialogue-menu")) {
            String section = args[1].toLowerCase(Locale.ROOT);
            if (section.equals("market")) openMarket(player);
            else openHubMenu(player, section);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("dialogue-tutorial")) {
            advanceTutorial(player, 1, 2, "튜토리얼 1/6", "대화 완료 · Shift+손 바꾸기로 GUI 열기");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            int page = 1;
            try { if (args.length > 1) page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
            openHelpMenu(player, page);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("market")) { openMarket(player); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("sell")) { sellHand(player); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("tools")) { openToolbox(player); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("menu")) { openHubMenu(player, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "hub"); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("board")) return board(player, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("home")) return housing.command(player, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("mail")) return housing.mail(player, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("art")) return art.command(player, args);
        if (args.length > 0 && socialEconomy.command(player, args)) return true;
        if (args.length > 0 && exploration.command(player, args)) return true;
        if (args.length > 0 && endgame.command(player, args)) return true;
        showStatus(player);
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("marketplay.admin")) {
            sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length >= 6 && args[1].equalsIgnoreCase("money")) {
            Player target = Bukkit.getPlayerExact(args[3]);
            if (target == null) { sender.sendMessage(Component.text("온라인 플레이어를 찾을 수 없습니다.", NamedTextColor.RED)); return true; }
            PlayerProfile profile = profiles.get(target.getUniqueId());
            if (profile == null) { sender.sendMessage(Component.text("플레이어 데이터를 불러오는 중입니다.", NamedTextColor.RED)); return true; }
            long value;
            try { value = Long.parseLong(args[4]); }
            catch (NumberFormatException error) { sender.sendMessage(Component.text("금액은 정수여야 합니다.", NamedTextColor.RED)); return true; }
            boolean set = args[2].equalsIgnoreCase("set");
            if ((!set && !args[2].equalsIgnoreCase("add")) || value < 0) { sender.sendMessage(Component.text("사용법: /mp admin money add|set <플레이어> <0 이상 금액> <사유>", NamedTextColor.RED)); return true; }
            String reason = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
            String requestId = UUID.randomUUID().toString();
            profiles.changeMoney(profile, value, set, reason, requestId).whenComplete((balance, error) -> getServer().getScheduler().runTask(this, () -> {
                if (error != null) { getLogger().severe("관리자 경제 변경 실패: " + error.getMessage()); sender.sendMessage(Component.text("경제 변경에 실패했습니다.", NamedTextColor.RED)); return; }
                synchronized (profile) { profile.setMoney(balance); }
                sender.sendMessage(Component.text(target.getName() + " 잔액: " + balance + "원", NamedTextColor.GREEN));
            }));
            return true;
        }
        if (args.length >= 5 && args[1].equalsIgnoreCase("item") && args[2].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[3]);
            Material material = Material.matchMaterial(args[4]);
            if (target == null || material == null || !material.isItem()) { sender.sendMessage(Component.text("플레이어 또는 아이템이 올바르지 않습니다.", NamedTextColor.RED)); return true; }
            int amount = 1;
            try { if (args.length > 5) amount = Integer.parseInt(args[5]); }
            catch (NumberFormatException error) { sender.sendMessage(Component.text("수량은 정수여야 합니다.", NamedTextColor.RED)); return true; }
            if (amount < 1 || amount > material.getMaxStackSize()) { sender.sendMessage(Component.text("수량이 허용 범위를 벗어났습니다.", NamedTextColor.RED)); return true; }
            ItemStack item = new ItemStack(material, amount);
            tag(item);
            target.getInventory().addItem(item).values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            sender.sendMessage(Component.text(target.getName() + "에게 " + material + " " + amount + "개를 지급했습니다.", NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("사용법: /mp admin money add|set ... | /mp admin item give ...", NamedTextColor.YELLOW));
        return true;
    }

    private boolean board(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("/mp board post <60자 이내 글> · /mp board remove <번호>", NamedTextColor.AQUA));
            bulletinPosts.forEach(post -> player.sendMessage(Component.text("[" + post.shortId() + "] " + post.authorName() + ": " + post.body(), NamedTextColor.GRAY)));
            return true;
        }
        if (args[1].equalsIgnoreCase("post") && args.length >= 3) {
            String body = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            profiles.postBulletin(player.getUniqueId(), player.getName(), body, Instant.now(), Duration.ofMinutes(5), Duration.ofDays(1))
                    .whenComplete((post, error) -> getServer().getScheduler().runTask(this, () -> {
                        if (error != null) { player.sendMessage(Component.text("글은 60자 이내이며 5분마다 작성할 수 있습니다.", NamedTextColor.RED)); return; }
                        player.sendMessage(Component.text("게시글 등록: " + post.shortId(), NamedTextColor.GREEN));
                        refreshBulletins();
                    }));
            return true;
        }
        if (args[1].equalsIgnoreCase("remove") && args.length == 3) {
            profiles.deleteBulletin(args[2], player.getUniqueId(), player.hasPermission("marketplay.admin"))
                    .whenComplete((deleted, error) -> getServer().getScheduler().runTask(this, () -> {
                        boolean success = error == null && deleted;
                        player.sendMessage(Component.text(success ? "게시글을 삭제했습니다." : "삭제할 수 있는 게시글을 찾지 못했습니다.", success ? NamedTextColor.GREEN : NamedTextColor.RED));
                        if (success) refreshBulletins();
                    }));
            return true;
        }
        player.sendMessage(Component.text("/mp board post <글> · /mp board remove <번호>", NamedTextColor.YELLOW));
        return true;
    }

    private void showStatus(Player player) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) { message(player, "플레이어 데이터를 불러오는 중입니다.", NamedTextColor.RED); return; }
        player.sendMessage(Component.text("시장놀이", NamedTextColor.GOLD));
        player.sendMessage(Component.text("돈 " + profile.money() + "원 · 내공 " + profile.innerPower() + " · 계급 " + ranks.rankFor(profile.innerPower()), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(String.format(Locale.ROOT, "활력 %.1f / %.1f", profile.vitality(), maximumVitality), NamedTextColor.AQUA));
        StringBuilder skills = new StringBuilder();
        for (Skill skill : Skill.values()) {
            if (!skills.isEmpty()) skills.append(" · ");
            skills.append(skill.displayName()).append(' ').append(profile.level(skill));
        }
        player.sendMessage(Component.text(skills.toString(), NamedTextColor.GREEN));
        player.sendMessage(Component.text("로비 안내 NPC 또는 /mp menu에서 채집·탐험·사냥 지역으로 이동하세요.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/mp exchange · stall · restaurant · guild · service 로 사회경제 활동을 시작하세요.", NamedTextColor.AQUA));
    }

    private void load(Player player) {
        busy.add(player.getUniqueId());
        profiles.load(player.getUniqueId()).whenComplete((profile, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) {
                getLogger().severe("플레이어 데이터 로드 실패: " + error.getMessage());
                player.kick(Component.text("시장놀이 데이터를 불러오지 못했습니다."));
                return;
            }
            recoverSale(player, profile);
        }));
    }

    private void recoverSale(Player player, PlayerProfile profile) {
        profiles.pendingSale(player.getUniqueId()).whenComplete((pending, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) { player.kick(Component.text("판매 정산 상태를 확인하지 못했습니다.")); return; }
            if (pending.isEmpty()) { recoverPlayerSystems(player); return; }
            ProfileStore.SaleIntent sale = pending.get();
            ItemStack marked = findMarked(player, saleIntentKey, sale.id());
            if (sale.state().equals("PREPARED") || marked != null) {
                if (marked != null) {
                    marked.editPersistentDataContainer(data -> data.remove(saleIntentKey));
                    player.saveData();
                }
                profiles.cancelSale(sale.id()).whenComplete((ignored, cancelError) -> getServer().getScheduler().runTask(this, () -> {
                    if (cancelError != null) player.kick(Component.text("판매 취소 복구에 실패했습니다."));
                    else recoverPlayerSystems(player);
                }));
                return;
            }
            profiles.completeSale(profile, sale.id()).whenComplete((balance, completeError) -> getServer().getScheduler().runTask(this, () -> {
                if (completeError != null) { player.kick(Component.text("판매 정산 복구에 실패했습니다.")); return; }
                synchronized (profile) { profile.setMoney(balance); }
                player.sendMessage(Component.text("중단됐던 판매 대금이 정산되었습니다.", NamedTextColor.GREEN));
                recoverPlayerSystems(player);
            }));
        }));
    }

    private void recoverPlayerSystems(Player player) {
        housing.recover(player, () -> exploration.recover(player, () -> socialEconomy.recover(player, () -> endgame.recover(player, () -> deliverPendingGrants(player)))));
    }

    void deliverPendingGrants(Player player) {
        profiles.pendingGrants(player.getUniqueId()).whenComplete((grants, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) { player.kick(Component.text("구매 물품을 확인하지 못했습니다.")); return; }
            if (!player.isOnline()) return;
            PlayerProfile profile = profiles.get(player.getUniqueId());
            if (profile == null) return;
            Set<String> owned = new HashSet<>();
            Set<String> absorbed = new HashSet<>();
            ArrayList<ProfileStore.ItemGrant> remaining = new ArrayList<>();
            try {
                for (ProfileStore.ItemGrant grant : grants) {
                    String toolId = ItemStack.deserializeBytes(grant.item()).getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
                    if (toolId != null && tools.containsKey(toolId)) {
                        owned.add(toolId);
                        if (!toolId.equals("old_rod")) { absorbed.add(grant.id()); continue; }
                    }
                    remaining.add(grant);
                }
            } catch (RuntimeException invalidItem) {
                player.kick(Component.text("구매 물품 데이터가 손상되었습니다."));
                return;
            }
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item == null) continue;
                String toolId = item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
                if (toolId != null && tools.containsKey(toolId)) owned.add(toolId);
            }
            String offHandTool = player.getInventory().getItemInOffHand().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
            if (offHandTool != null && tools.containsKey(offHandTool)) owned.add(offHandTool);
            profiles.migrateTools(profile, owned, absorbed).whenComplete((ignored, migrationError) -> getServer().getScheduler().runTask(this, () -> {
                if (migrationError != null) { player.kick(Component.text("생활도구 이전에 실패했습니다.")); return; }
                if (!player.isOnline()) return;
                owned.forEach(profile::addTool);
                for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
                    ItemStack item = player.getInventory().getItem(slot);
                    if (item == null) continue;
                    String toolId = item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
                    if (toolId != null && tools.containsKey(toolId) && !toolId.equals("old_rod")) player.getInventory().setItem(slot, null);
                }
                String migratedOffHand = player.getInventory().getItemInOffHand().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
                if (migratedOffHand != null && tools.containsKey(migratedOffHand) && !migratedOffHand.equals("old_rod")) player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                player.saveData();
                remaining.forEach(grant -> deliverGrant(player, grant));
                busy.remove(player.getUniqueId());
                if (profile.tutorialStep() == 0) startTutorial(player, profile);
                else if (profile.tutorialStep() < 8) resumeTutorial(player, profile);
            }));
        }));
    }

    private void deliverGrant(Player player, ProfileStore.ItemGrant grant) {
        if (findMarked(player, grantKey, grant.id()) == null) {
            ItemStack item = ItemStack.deserializeBytes(grant.item());
            if (!player.getInventory().addItem(item).isEmpty()) {
                player.sendMessage(Component.text("인벤토리를 비우고 다시 접속하면 구매 물품을 받습니다.", NamedTextColor.YELLOW));
                return;
            }
            player.saveData();
        }
        profiles.acknowledgeGrant(grant.id()).exceptionally(error -> {
            getLogger().severe("구매 물품 확인 저장 실패: " + error.getMessage());
            return null;
        });
    }

    private ItemStack findMarked(Player player, NamespacedKey key, String value) {
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && value.equals(item.getPersistentDataContainer().get(key, PersistentDataType.STRING))) return item;
        return null;
    }

    void openHubMenu(Player player, String section) {
        if (section.equals("travel")) { showTravelGuide(player); return; }
        if (section.equals("adventure")) { showAdventureGuide(player); return; }
        if (section.equals("board")) { openBoardMenu(player); return; }
        if (section.equals("housing")) { openHousingMenu(player); return; }
        if (section.equals("status")) { openStatusMenu(player); return; }
        Inventory menu = Bukkit.createInventory(null, 27, HUB_TITLE);
        menu.setItem(10, menuItem(Material.EMERALD, "생활도구 시장", "market", "도구 구매와 자원 판매"));
        menu.setItem(11, menuItem(Material.CHEST, "생활도구함", "tools", "구매한 생활도구 확인"));
        menu.setItem(12, menuItem(Material.WRITABLE_BOOK, "시장 게시판", "board", "시세·왕실 주문·주민 게시글"));
        menu.setItem(13, menuItem(Material.COMPASS, "여행 안내", "travel", "남쪽 끝 채집소 · 북쪽 끝 탐험지"));
        menu.setItem(14, menuItem(Material.RED_BED, "주택 안내", "housing", "개인 집과 우편"));
        menu.setItem(15, menuItem(Material.FILLED_MAP, "그림과 전시", "art", "작품 제작·전시·거래"));
        menu.setItem(16, menuItem(Material.COOKED_BEEF, "레스토랑", "restaurant", "요리와 협동 영업"));
        menu.setItem(22, menuItem(Material.CHEST_MINECART, "상단", "guild", "공동 창고와 프로젝트"));
        menu.setItem(21, menuItem(Material.IRON_SWORD, "모험과 사냥", "adventure", "동쪽 끝 던전·무한 탑·후반 마을"));
        menu.setItem(20, menuItem(Material.PLAYER_HEAD, "내 상태", "status", "돈·계급·내공·활력·숙련도"));
        menu.setItem(18, menuItem(Material.KNOWLEDGE_BOOK, "도움말", "help:1", "기능과 명령어를 페이지별로 확인"));
        player.openInventory(menu);
    }

    private void openHelpMenu(Player player, int requestedPage) {
        int page = Math.max(1, Math.min(3, requestedPage));
        Inventory menu = Bukkit.createInventory(null, 27, HELP_TITLE);
        if (page == 1) {
            menu.setItem(10, menuItem(Material.COMPASS, "기본 조작", "none", "Shift+손 바꾸기: 메뉴 · NPC 우클릭: 대화"));
            menu.setItem(12, menuItem(Material.EMERALD, "/mp market · sell", "none", "도구 구매 · 손에 든 자원 판매"));
            menu.setItem(14, menuItem(Material.CHEST, "/mp tools · menu", "none", "도구함 · 종합 메뉴"));
            menu.setItem(16, menuItem(Material.PLAYER_HEAD, "/mp", "none", "돈·계급·활력·숙련도 확인"));
        } else if (page == 2) {
            menu.setItem(10, menuItem(Material.WRITABLE_BOOK, "/mp board", "none", "게시판 읽기·작성·삭제"));
            menu.setItem(12, menuItem(Material.OAK_DOOR, "/mp home · mail", "none", "개인 주택·우편·초대"));
            menu.setItem(14, menuItem(Material.FILLED_MAP, "/mp art", "none", "작품 제작·전시·거래"));
            menu.setItem(16, menuItem(Material.CHEST_MINECART, "exchange · stall", "none", "교환 목록·개인 상점"));
        } else {
            menu.setItem(10, menuItem(Material.COOKED_BEEF, "restaurant · service", "none", "협동 식당·주민 서비스"));
            menu.setItem(12, menuItem(Material.CHEST_MINECART, "/mp guild", "none", "상단 가입·창고·공동 프로젝트"));
            menu.setItem(14, menuItem(Material.IRON_SWORD, "explore · dungeon", "none", "탐험·사냥·던전"));
            menu.setItem(16, menuItem(Material.NETHER_STAR, "tower · dragon · heaven", "none", "무한 탑·용·후반 성장"));
        }
        menu.setItem(18, menuItem(Material.ARROW, "이전 페이지", "help:" + Math.max(1, page - 1), page + " / 3"));
        menu.setItem(22, menuItem(Material.BARRIER, "종합 메뉴", "hub", "시장놀이 메뉴로 돌아가기"));
        menu.setItem(26, menuItem(Material.ARROW, "다음 페이지", "help:" + Math.min(3, page + 1), page + " / 3"));
        player.openInventory(menu);
    }

    private void showTravelGuide(Player player) { player.sendActionBar(Component.text("로비 남쪽 끝 → 채집소 · 북쪽 끝 → 탐험지", NamedTextColor.AQUA)); }
    private void showAdventureGuide(Player player) { player.sendActionBar(Component.text("로비 동쪽 끝: 북쪽 던전 · 중앙 무한 탑 · 남쪽 후반 마을", NamedTextColor.RED)); }

    private void openBoardMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, BOARD_TITLE);
        menu.setItem(4, menuItem(Material.CLOCK, "오늘의 시장", "board-chat", marketText().replace('\n', ' ')));
        int slot = 10;
        for (ProfileStore.BulletinPost post : bulletinPosts) {
            if (slot > 16) break;
            menu.setItem(slot++, menuItem(Material.PAPER, post.authorName() + "의 글", "board-chat", "[" + post.shortId() + "] " + post.body()));
        }
        menu.setItem(22, menuItem(Material.WRITABLE_BOOK, "게시판 자세히 보기", "board-chat", "채팅에서 작성·삭제 명령도 확인"));
        player.openInventory(menu);
    }

    private void openHousingMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, HOUSING_TITLE);
        menu.setItem(10, menuItem(Material.OAK_DOOR, "내 집", "home", "내 집 상태 확인 또는 입장"));
        menu.setItem(12, menuItem(Material.GRASS_BLOCK, "집 만들기", "home-create", "개인 하우징 월드 생성"));
        menu.setItem(14, menuItem(Material.CHEST, "우편함", "mail", "편지·선물·초대 확인"));
        menu.setItem(16, menuItem(Material.BELL, "중앙 로비", "lobby", "광장으로 돌아가기"));
        player.openInventory(menu);
    }

    private void openStatusMenu(Player player) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) { message(player, "플레이어 데이터를 불러오는 중입니다.", NamedTextColor.RED); return; }
        Inventory menu = Bukkit.createInventory(null, 9, STATUS_TITLE);
        menu.setItem(0, menuItem(Material.EMERALD, "보유 금액", "status", profile.money() + "원"));
        menu.setItem(2, menuItem(Material.NETHER_STAR, "계급", "status", ranks.rankFor(profile.innerPower())));
        menu.setItem(4, menuItem(Material.BLAZE_POWDER, "내공", "status", String.valueOf(profile.innerPower())));
        menu.setItem(6, menuItem(Material.RED_DYE, "활력", "status", String.format(Locale.ROOT, "%.1f / %.1f", profile.vitality(), maximumVitality)));
        ItemStack skills = menuItem(Material.EXPERIENCE_BOTTLE, "생활 숙련도", "status", "아래 항목을 확인하세요");
        skills.editMeta(meta -> meta.lore(Arrays.stream(Skill.values()).map(skill -> Component.text(skill.displayName() + " " + profile.level(skill), NamedTextColor.GRAY)).toList()));
        menu.setItem(8, skills);
        player.openInventory(menu);
    }

    private ItemStack menuItem(Material material, String name, String action, String description) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.GOLD));
            meta.lore(List.of(Component.text(description, NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(hubActionKey, PersistentDataType.STRING, action);
        });
        return item;
    }

    private void runHubAction(Player player, String action) {
        if (action.startsWith("help:")) { openHelpMenu(player, Integer.parseInt(action.substring(5))); return; }
        switch (action) {
            case "market" -> openMarket(player);
            case "tools" -> openToolbox(player);
            case "sell" -> sellHand(player);
            case "board" -> openBoardMenu(player);
            case "board-chat" -> board(player, new String[]{"board"});
            case "travel" -> showTravelGuide(player);
            case "adventure" -> showAdventureGuide(player);
            case "housing" -> openHousingMenu(player);
            case "status" -> openStatusMenu(player);
            case "home" -> player.performCommand("marketplay home");
            case "home-create" -> player.performCommand("marketplay home create");
            case "mail" -> player.performCommand("marketplay mail");
            case "art" -> player.performCommand("marketplay art");
            case "restaurant" -> player.performCommand("marketplay restaurant");
            case "guild" -> player.performCommand("marketplay guild");
            case "none" -> { }
            default -> openHubMenu(player, "hub");
        }
    }

    void teleportLobby(Player player) { if (hubReady) hub.teleport(player); }

    private void startTutorial(Player player, PlayerProfile profile) {
        profile.setTutorialStep(1);
        saveProfile(profile);
        player.teleportAsync(hub.tutorialSpawn());
        tutorialDisplay(player, "튜토리얼 시작", "안내인에게 우클릭해 대화를 시작하세요");
    }

    private void resumeTutorial(Player player, PlayerProfile profile) {
        player.teleportAsync(hub.tutorialSpawn());
        String instruction = switch (profile.tutorialStep()) {
            case 1 -> "안내인에게 우클릭하세요";
            case 2 -> "Shift+손 바꾸기로 GUI를 여세요";
            case 3 -> "도구 상자를 우클릭하세요";
            case 4 -> "통나무를 우클릭하세요";
            case 5 -> "열매를 우클릭해 채집하세요";
            case 6 -> "훈련용 몬스터를 처치하세요";
            default -> "길 끝 출구로 이동하세요";
        };
        tutorialDisplay(player, "튜토리얼 이어하기", instruction);
        if (profile.tutorialStep() == 6) hub.spawnTutorialMonster();
    }

    private void advanceTutorial(Player player, int expected, int next, String title, String subtitle) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null || profile.tutorialStep() != expected) return;
        profile.setTutorialStep(next);
        saveProfile(profile);
        tutorialDisplay(player, title, subtitle);
    }

    private void tutorialDisplay(Player player, String title, String subtitle) {
        player.showTitle(Title.title(Component.text(title, NamedTextColor.GOLD), Component.text(subtitle, NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(500))));
    }

    Location lobbyLocation() { return hub.spawn(); }
    World lobbyWorld() { return hub.world(); }

    private void openMarket(Player player) {
        if (busy.contains(player.getUniqueId()) || profiles.get(player.getUniqueId()) == null) {
            player.sendMessage(Component.text("플레이어 데이터를 확인하는 중입니다.", NamedTextColor.YELLOW));
            return;
        }
        Inventory market = Bukkit.createInventory(null, 9, MARKET_TITLE);
        int slot = 0;
        for (ToolDefinition definition : tools.values()) market.setItem(slot++, tool(definition));
        ItemStack sell = new ItemStack(Material.HOPPER);
        sell.editMeta(meta -> meta.displayName(Component.text("손에 든 자원 판매", NamedTextColor.GREEN)));
        market.setItem(8, sell);
        ItemStack toolbox = new ItemStack(Material.CHEST);
        toolbox.editMeta(meta -> meta.displayName(Component.text("생활도구함 열기", NamedTextColor.AQUA)));
        market.setItem(7, toolbox);
        player.openInventory(market);
    }

    private void openToolbox(Player player) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (busy.contains(player.getUniqueId()) || profile == null) return;
        Inventory toolbox = Bukkit.createInventory(null, 9, TOOLBOX_TITLE);
        int slot = 0;
        for (ToolDefinition definition : tools.values()) {
            if (profile.hasTool(definition.id())) {
                ItemStack owned = tool(definition);
                owned.editMeta(meta -> meta.displayName(Component.text(definition.name() + (definition.id().equals("old_rod") ? " · 직접 사용" : " · 자동 사용"), NamedTextColor.GREEN)));
                toolbox.setItem(slot, owned);
            }
            slot++;
        }
        player.openInventory(toolbox);
    }

    private ItemStack tool(ToolDefinition definition) {
        ItemStack item = new ItemStack(definition.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(definition.name() + " · " + definition.price() + "원", NamedTextColor.YELLOW));
            meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, definition.id());
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, "tool:" + definition.id());
            meta.getPersistentDataContainer().set(itemSchemaKey, PersistentDataType.INTEGER, 1);
        });
        return item;
    }

    private String toolInHand(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        return item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    private void purchaseTool(Player player, String toolId) {
        ToolDefinition definition = tools.get(toolId);
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (definition == null || profile == null || !busy.add(player.getUniqueId())) return;
        player.closeInventory();
        String requestId = UUID.randomUUID().toString();
        ItemStack item = definition.id().equals("old_rod") ? tool(definition) : null;
        if (item != null) item.editMeta(meta -> meta.getPersistentDataContainer().set(grantKey, PersistentDataType.STRING, requestId));
        byte[] physicalItem = item == null ? null : item.serializeAsBytes();
        profiles.purchaseTool(profile, definition.price(), definition.id(), physicalItem, requestId)
                .whenComplete((balance, error) -> getServer().getScheduler().runTask(this, () -> {
                    if (error != null) {
                        busy.remove(player.getUniqueId());
                        player.sendMessage(Component.text("돈이 부족하거나 구매 처리에 실패했습니다.", NamedTextColor.RED));
                        return;
                    }
                    synchronized (profile) { profile.setMoney(balance); profile.addTool(definition.id()); }
                    if (player.isOnline()) {
                        if (physicalItem != null) deliverGrant(player, new ProfileStore.ItemGrant(requestId, physicalItem));
                        player.sendMessage(Component.text(definition.name() + "을 구매했습니다. 잔액 " + balance + "원", NamedTextColor.GREEN));
                    }
                    busy.remove(player.getUniqueId());
                }));
    }

    void harvest(Player player, String cooldownNamespace, String itemId, String name, Material material, Skill skill, String toolId) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null || busy.contains(player.getUniqueId())) return;
        if (!profile.hasTool(toolId)) {
            player.sendActionBar(Component.text(name + " 필요 도구: " + tools.get(toolId).name(), NamedTextColor.RED));
            return;
        }
        String cooldownKey = player.getUniqueId() + ":" + cooldownNamespace;
        long now = System.currentTimeMillis();
        long readyAt = nodeCooldowns.getOrDefault(cooldownKey, 0L);
        if (readyAt > now) {
            player.sendActionBar(Component.text("자원이 재생 중입니다. " + ((readyAt - now + 999) / 1000) + "초", NamedTextColor.YELLOW));
            return;
        }
        ItemStack reward = new ItemStack(material);
        tag(reward, itemId);
        reward.editMeta(meta -> meta.displayName(Component.text(name + " ★", NamedTextColor.GREEN)));
        if (!player.getInventory().addItem(reward).isEmpty()) {
            player.sendActionBar(Component.text("인벤토리 공간이 없습니다.", NamedTextColor.RED));
            return;
        }
        nodeCooldowns.put(cooldownKey, now + getConfig().getLong("resource-node-cooldown-millis", 5000));
        rewardActivity(player, skill);
        player.sendActionBar(Component.text(name + " 획득 · " + skill.displayName() + " +1", NamedTextColor.GREEN));
    }

    private void sellHand(Player player) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null || !busy.add(player.getUniqueId())) return;
        ItemStack original = player.getInventory().getItemInMainHand().clone();
        String itemId = original.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        Long unitPrice = itemId == null ? null : prices.get(itemId);
        if (unitPrice == null || original.getType().isAir()) {
            busy.remove(player.getUniqueId());
            player.sendMessage(Component.text("판매 가능한 시장놀이 자원을 주 손에 들어주세요.", NamedTextColor.YELLOW));
            return;
        }
        String intentId = UUID.randomUUID().toString();
        profiles.beginSale(profile, intentId, original.serializeAsBytes(), itemId, original.getAmount(), unitPrice)
                .whenComplete((ignored, beginError) -> getServer().getScheduler().runTask(this, () -> {
                    if (beginError != null) { failSale(player, "판매 준비에 실패했습니다.", beginError); return; }
                    if (!player.isOnline() || !sameStack(player.getInventory().getItemInMainHand(), original)) {
                        profiles.cancelSale(intentId);
                        busy.remove(player.getUniqueId());
                        return;
                    }
                    ItemStack marked = player.getInventory().getItemInMainHand();
                    marked.editPersistentDataContainer(data -> data.set(saleIntentKey, PersistentDataType.STRING, intentId));
                    player.saveData();
                    profiles.markSaleRemoving(intentId).whenComplete((removed, markError) -> getServer().getScheduler().runTask(this, () -> {
                        if (markError != null) {
                            marked.editPersistentDataContainer(data -> data.remove(saleIntentKey));
                            player.saveData();
                            profiles.cancelSale(intentId);
                            failSale(player, "판매 잠금에 실패했습니다.", markError);
                            return;
                        }
                        if (!player.isOnline() || findMarked(player, saleIntentKey, intentId) == null) {
                            busy.remove(player.getUniqueId());
                            return;
                        }
                        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                        player.saveData();
                        completeSale(player, profile, intentId, original.getAmount(), unitPrice);
                    }));
                }));
    }

    private void completeSale(Player player, PlayerProfile profile, String intentId, int quantity, long unitPrice) {
        profiles.completeSale(profile, intentId).whenComplete((balance, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) {
                getLogger().severe("판매 정산 실패, 자동 복구 대기: " + error.getMessage());
                getServer().getScheduler().runTaskLater(this, () -> recoverSale(player, profile), 20L);
                return;
            }
            synchronized (profile) { profile.setMoney(balance); }
            busy.remove(player.getUniqueId());
            player.sendMessage(Component.text(quantity + "개 판매 · +" + Math.multiplyExact(quantity, unitPrice) + "원 · 잔액 " + balance + "원", NamedTextColor.GREEN));
        }));
    }

    private void failSale(Player player, String message, Throwable error) {
        busy.remove(player.getUniqueId());
        getLogger().severe(message + " " + error.getMessage());
        if (player.isOnline()) player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private boolean sameStack(ItemStack current, ItemStack expected) {
        return current.getAmount() == expected.getAmount() && current.isSimilar(expected);
    }

    private void regenerateVitality() {
        double amount = getConfig().getDouble("vitality-regeneration-per-minute", 2.0);
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerProfile profile = profiles.get(player.getUniqueId());
            if (profile != null) profile.restoreVitality(amount, maximumVitality);
        }
    }

    private void reloadRuntimeConfig() {
        maximumVitality = getConfig().getDouble("maximum-vitality", 100.0);
        activityCost = getConfig().getDouble("activity-vitality-cost", 1.0);
        if (maximumVitality <= 0 || activityCost < 0) throw new IllegalArgumentException("활력 설정은 0보다 커야 합니다.");
        if (getConfig().getLong("fishing-reel-window-millis", 1500) <= 0) throw new IllegalArgumentException("낚시 제한 시간은 0보다 커야 합니다.");
        LinkedHashMap<String, Long> rankValues = new LinkedHashMap<>();
        ConfigurationSection rankSection = getConfig().getConfigurationSection("ranks");
        if (rankSection != null) for (String key : rankSection.getKeys(false)) rankValues.put(key, rankSection.getLong(key));
        ranks = new RankTable(rankValues);
        activityBlocks.clear();
        ConfigurationSection activities = getConfig().getConfigurationSection("activities");
        if (activities != null) for (String name : activities.getKeys(false)) {
            Skill skill = Skill.byDisplayName(name);
            for (String material : activities.getStringList(name)) activityBlocks.put(Material.valueOf(material), skill);
        }
        long toolPrice = getConfig().getLong("starter-tool-price", 100);
        if (toolPrice <= 0) throw new IllegalArgumentException("초보 도구 가격은 0보다 커야 합니다.");
        tools.clear();
        tools.put("old_net", new ToolDefinition("old_net", "낡은 망", Material.BRUSH, toolPrice));
        tools.put("old_hoe", new ToolDefinition("old_hoe", "낡은 호미", Material.WOODEN_HOE, toolPrice));
        tools.put("old_axe", new ToolDefinition("old_axe", "낡은 도끼", Material.WOODEN_AXE, toolPrice));
        tools.put("old_shears", new ToolDefinition("old_shears", "낡은 가위", Material.SHEARS, toolPrice));
        tools.put("old_pickaxe", new ToolDefinition("old_pickaxe", "낡은 곡괭이", Material.WOODEN_PICKAXE, toolPrice));
        tools.put("old_rod", new ToolDefinition("old_rod", "낡은 낚싯대", Material.FISHING_ROD, toolPrice));
        basePrices.clear();
        ConfigurationSection priceSection = getConfig().getConfigurationSection("prices");
        if (priceSection != null) for (String itemId : priceSection.getKeys(false)) {
            long price = priceSection.getLong(itemId);
            if (price <= 0) throw new IllegalArgumentException("판매 가격은 0보다 커야 합니다: " + itemId);
            basePrices.put(itemId, price);
        }
        if (profiles != null) refreshMarketDay();
    }

    Map<String, Long> prices() { return Map.copyOf(prices); }
    String marketText() { return marketDay == null ? "오늘의 시장\n가격 준비 중" : MarketText.render(marketDay); }
    List<ProfileStore.BulletinPost> bulletins() { return List.copyOf(bulletinPosts); }

    private void refreshMarketDay() {
        if (profiles == null || basePrices.isEmpty()) return;
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (marketDay != null && marketDay.date().equals(today)) return;
        profiles.marketDay(today, basePrices, ZoneId.systemDefault()).whenComplete((loaded, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) { getLogger().severe("오늘의 시장 생성 실패: " + error.getMessage()); return; }
            marketDay = loaded;
            prices.clear();
            prices.putAll(loaded.prices());
            if (hubReady) hub.updateDisplays(hub.world());
        }));
    }

    private void refreshBulletins() {
        if (profiles == null) return;
        profiles.bulletins(Instant.now(), 3).whenComplete((loaded, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) { getLogger().severe("게시판 로드 실패: " + error.getMessage()); return; }
            bulletinPosts = loaded;
            if (hubReady) hub.updateDisplays(hub.world());
        }));
    }
    private void tag(ItemStack item) {
        tag(item, item.getType().name().toLowerCase(Locale.ROOT));
    }

    private void tag(ItemStack item, String itemId) {
        item.editPersistentDataContainer(data -> {
            data.set(itemIdKey, PersistentDataType.STRING, itemId);
            data.set(itemSchemaKey, PersistentDataType.INTEGER, 1);
            data.set(qualityKey, PersistentDataType.INTEGER, 1);
        });
    }

    private boolean message(Player player, String text, NamedTextColor color) { player.sendMessage(Component.text(text, color)); return true; }

    PlayerProfile profile(UUID playerId) { return profiles.get(playerId); }
    void saveProfile(PlayerProfile profile) { profiles.save(profile).exceptionally(error -> { getLogger().severe("플레이어 저장 실패: " + error.getMessage()); return null; }); }
    NamespacedKey grantKey() { return grantKey; }
    double maximumVitality() { return maximumVitality; }
    double activityCost() { return activityCost; }
    boolean atLeast(PlayerProfile profile, String rank) { return ranks.atLeast(profile.innerPower(), rank); }
    boolean tryLock(UUID playerId) { return busy.add(playerId); }
    void lock(UUID playerId) { busy.add(playerId); }
    void unlock(UUID playerId) { busy.remove(playerId); }

    private record ToolDefinition(String id, String name, Material material, long price) {}
}
