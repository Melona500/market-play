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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;

public final class MarketPlayPlugin extends JavaPlugin implements Listener {
    private ProfileStore profiles;
    private RankTable ranks;
    private final Map<Material, Skill> activityBlocks = new EnumMap<>(Material.class);
    private NamespacedKey qualityKey;
    private NamespacedKey itemIdKey;
    private NamespacedKey itemSchemaKey;
    private double maximumVitality;
    private double activityCost;
    private final Set<UUID> pendingSales = ConcurrentHashMap.newKeySet();

    @Override public void onEnable() {
        saveDefaultConfig();
        qualityKey = new NamespacedKey(this, "quality");
        itemIdKey = new NamespacedKey(this, "item_id");
        itemSchemaKey = new NamespacedKey(this, "item_schema");
        reloadRuntimeConfig();
        try { profiles = new ProfileStore(getDataFolder().toPath().resolve("marketplay.db"), getConfig().getLong("starting-money", 1000), maximumVitality); }
        catch (Exception error) {
            getLogger().severe("데이터베이스 시작 실패: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
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
        profiles.unload(event.getPlayer().getUniqueId()).exceptionally(error -> {
            getLogger().severe("플레이어 데이터 저장 실패: " + error.getMessage());
            return null;
        });
    }

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
        if (args.length > 0 && args[0].equalsIgnoreCase("sell")) return sellHand(player);
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
            profiles.changeMoney(profile, value, set, reason).whenComplete((balance, error) -> getServer().getScheduler().runTask(this, () -> {
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

    private boolean sellHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) return message(player, "판매할 아이템을 손에 들어주세요.", NamedTextColor.RED);
        long unitPrice = getConfig().getLong("market-prices." + held.getType().name(), -1);
        if (unitPrice < 0) return message(player, "현재 시장에서 매입하지 않는 물품입니다.", NamedTextColor.RED);
        int quality = held.getPersistentDataContainer().getOrDefault(qualityKey, PersistentDataType.INTEGER, 1);
        double multiplier = getConfig().getDouble("quality-multipliers." + quality, 1.0);
        unitPrice = Math.max(0, Math.round(unitPrice * multiplier));
        int quantity = held.getAmount();
        long total;
        try { total = Math.multiplyExact(unitPrice, quantity); }
        catch (ArithmeticException error) { return message(player, "판매 금액이 허용 범위를 넘었습니다.", NamedTextColor.RED); }
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile == null) return message(player, "플레이어 데이터를 불러오는 중입니다.", NamedTextColor.RED);
        if (!pendingSales.add(player.getUniqueId())) return message(player, "이전 판매를 처리 중입니다.", NamedTextColor.RED);
        ItemStack original = held.clone();
        player.getInventory().setItemInMainHand(null);
        String itemId = held.getPersistentDataContainer().getOrDefault(itemIdKey, PersistentDataType.STRING, held.getType().name().toLowerCase(Locale.ROOT));
        profiles.sell(profile, itemId, quantity, unitPrice).whenComplete((balance, error) ->
            getServer().getScheduler().runTask(this, () -> {
                pendingSales.remove(player.getUniqueId());
                if (error != null) {
                    player.getInventory().addItem(original).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                    getLogger().severe("판매 저장 실패: " + error.getMessage());
                    message(player, "저장 실패로 거래가 취소되었습니다.", NamedTextColor.RED);
                    return;
                }
                synchronized (profile) { profile.setMoney(balance); }
                message(player, quantity + "개 판매 · " + total + "원 획득", NamedTextColor.GOLD);
            }));
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
    }

    private void load(Player player) {
        profiles.load(player.getUniqueId()).exceptionally(error -> {
            getLogger().severe("플레이어 데이터 로드 실패: " + error.getMessage());
            getServer().getScheduler().runTask(this, () -> player.kick(Component.text("시장놀이 데이터를 불러오지 못했습니다.")));
            return null;
        });
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
    }

    private void tag(ItemStack item) {
        item.editPersistentDataContainer(data -> {
            data.set(itemIdKey, PersistentDataType.STRING, item.getType().name().toLowerCase(Locale.ROOT));
            data.set(itemSchemaKey, PersistentDataType.INTEGER, 1);
            data.set(qualityKey, PersistentDataType.INTEGER, 1);
        });
    }

    private boolean message(Player player, String text, NamedTextColor color) { player.sendMessage(Component.text(text, color)); return true; }
}
