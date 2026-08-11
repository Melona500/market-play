package kr.hyuni.marketplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketPlayPlugin extends JavaPlugin implements Listener {
    private static final Component MARKET_TITLE = Component.text("생활도구 상점", NamedTextColor.GOLD);
    private ProfileStore profiles;
    private RankTable ranks;
    private final Map<Material, Skill> activityBlocks = new EnumMap<>(Material.class);
    private NamespacedKey qualityKey;
    private NamespacedKey itemIdKey;
    private NamespacedKey itemSchemaKey;
    private NamespacedKey toolKey;
    private NamespacedKey grantKey;
    private NamespacedKey saleIntentKey;
    private HubBuilder hub;
    private boolean hubReady;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nodeCooldowns = new ConcurrentHashMap<>();
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final Map<String, Long> prices = new LinkedHashMap<>();
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
        reloadRuntimeConfig();
        try { profiles = new ProfileStore(getDataFolder().toPath().resolve("marketplay.db"), getConfig().getLong("starting-money", 1000), maximumVitality); }
        catch (Exception error) {
            getLogger().severe("데이터베이스 시작 실패: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        hub = new HubBuilder(this);
        hubReady = hub.ensure(getServer().getWorlds().getFirst());
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers()) load(player);
        long period = 20L * 60L;
        getServer().getScheduler().runTaskTimer(this, this::regenerateVitality, period, period);
    }

    @Override public void onDisable() {
        if (profiles == null) return;
        try { profiles.close(); }
        catch (Exception error) { getLogger().severe("플레이어 데이터 저장 실패: " + error.getMessage()); }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { load(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        busy.remove(event.getPlayer().getUniqueId());
        profiles.unload(event.getPlayer().getUniqueId()).exceptionally(error -> {
            getLogger().severe("플레이어 데이터 저장 실패: " + error.getMessage());
            return null;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!hubReady || event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
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
        for (HubBuilder.Node node : HubBuilder.NODES) if (node.location().matches(event.getClickedBlock())) {
            event.setCancelled(true);
            harvest(event.getPlayer(), node);
            return;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (busy.contains(player.getUniqueId())) { event.setCancelled(true); return; }
        if (!event.getView().title().equals(MARKET_TITLE)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        String toolId = clicked.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (toolId != null) purchaseTool(player, toolId);
        else if (clicked.getType() == Material.HOPPER) { player.closeInventory(); sellHand(player); }
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onHeld(PlayerItemHeldEvent event) { if (busy.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true) public void onBlockBreak(BlockBreakEvent event) {
        Skill skill = activityBlocks.get(event.getBlock().getType());
        if (skill != null) rewardActivity(event.getPlayer(), skill);
    }

    @EventHandler(ignoreCancelled = true) public void onBlockDrop(BlockDropItemEvent event) {
        if (!activityBlocks.containsKey(event.getBlockState().getType())) return;
        event.getItems().forEach(item -> tag(item.getItemStack()));
    }

    @EventHandler(ignoreCancelled = true) public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
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
        if (args.length > 0 && args[0].equalsIgnoreCase("market")) { openMarket(player); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("sell")) { sellHand(player); return true; }
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
        player.sendMessage(Component.text("광장 북쪽 상점에서 도구를 사고, 자원 지역에서 채집한 뒤 판매대에 파세요.", NamedTextColor.GRAY));
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
            if (pending.isEmpty()) { deliverPendingGrants(player); return; }
            ProfileStore.SaleIntent sale = pending.get();
            ItemStack marked = findMarked(player, saleIntentKey, sale.id());
            if (sale.state().equals("PREPARED") || marked != null) {
                if (marked != null) {
                    marked.editPersistentDataContainer(data -> data.remove(saleIntentKey));
                    player.saveData();
                }
                profiles.cancelSale(sale.id()).whenComplete((ignored, cancelError) -> getServer().getScheduler().runTask(this, () -> {
                    if (cancelError != null) player.kick(Component.text("판매 취소 복구에 실패했습니다."));
                    else deliverPendingGrants(player);
                }));
                return;
            }
            profiles.completeSale(profile, sale.id()).whenComplete((balance, completeError) -> getServer().getScheduler().runTask(this, () -> {
                if (completeError != null) { player.kick(Component.text("판매 정산 복구에 실패했습니다.")); return; }
                synchronized (profile) { profile.setMoney(balance); }
                player.sendMessage(Component.text("중단됐던 판매 대금이 정산되었습니다.", NamedTextColor.GREEN));
                deliverPendingGrants(player);
            }));
        }));
    }

    private void deliverPendingGrants(Player player) {
        profiles.pendingGrants(player.getUniqueId()).whenComplete((grants, error) -> getServer().getScheduler().runTask(this, () -> {
            if (error != null) { player.kick(Component.text("구매 물품을 확인하지 못했습니다.")); return; }
            for (ProfileStore.ItemGrant grant : grants) deliverGrant(player, grant);
            busy.remove(player.getUniqueId());
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
        player.openInventory(market);
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

    private void purchaseTool(Player player, String toolId) {
        ToolDefinition definition = tools.get(toolId);
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (definition == null || profile == null || !busy.add(player.getUniqueId())) return;
        player.closeInventory();
        String grantId = UUID.randomUUID().toString();
        ItemStack item = tool(definition);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(grantKey, PersistentDataType.STRING, grantId));
        profiles.purchase(profile, definition.price(), "tool:" + definition.id(), item.serializeAsBytes(), grantId)
                .whenComplete((balance, error) -> getServer().getScheduler().runTask(this, () -> {
                    if (error != null) {
                        busy.remove(player.getUniqueId());
                        player.sendMessage(Component.text("돈이 부족하거나 구매 처리에 실패했습니다.", NamedTextColor.RED));
                        return;
                    }
                    synchronized (profile) { profile.setMoney(balance); }
                    if (player.isOnline()) {
                        deliverGrant(player, new ProfileStore.ItemGrant(grantId, item.serializeAsBytes()));
                        player.sendMessage(Component.text(definition.name() + "을 구매했습니다. 잔액 " + balance + "원", NamedTextColor.GREEN));
                    }
                    busy.remove(player.getUniqueId());
                }));
    }

    private void harvest(Player player, HubBuilder.Node node) {
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null || busy.contains(player.getUniqueId())) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        String toolId = held.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (!node.toolId().equals(toolId)) {
            player.sendActionBar(Component.text(node.name() + " 필요 도구: " + tools.get(node.toolId()).name(), NamedTextColor.RED));
            return;
        }
        String cooldownKey = player.getUniqueId() + ":" + node.id();
        long now = System.currentTimeMillis();
        long readyAt = nodeCooldowns.getOrDefault(cooldownKey, 0L);
        if (readyAt > now) {
            player.sendActionBar(Component.text("자원이 재생 중입니다. " + ((readyAt - now + 999) / 1000) + "초", NamedTextColor.YELLOW));
            return;
        }
        ItemStack reward = new ItemStack(node.reward());
        tag(reward, node.id());
        reward.editMeta(meta -> meta.displayName(Component.text(node.name() + " ★", NamedTextColor.GREEN)));
        if (!player.getInventory().addItem(reward).isEmpty()) {
            player.sendActionBar(Component.text("인벤토리 공간이 없습니다.", NamedTextColor.RED));
            return;
        }
        nodeCooldowns.put(cooldownKey, now + getConfig().getLong("resource-node-cooldown-millis", 5000));
        rewardActivity(player, node.skill());
        player.sendActionBar(Component.text(node.name() + " 획득 · " + node.skill().displayName() + " +1", NamedTextColor.GREEN));
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
        prices.clear();
        ConfigurationSection priceSection = getConfig().getConfigurationSection("prices");
        if (priceSection != null) for (String itemId : priceSection.getKeys(false)) {
            long price = priceSection.getLong(itemId);
            if (price <= 0) throw new IllegalArgumentException("판매 가격은 0보다 커야 합니다: " + itemId);
            prices.put(itemId, price);
        }
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

    private record ToolDefinition(String id, String name, Material material, long price) {}
}
