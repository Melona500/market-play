package kr.hyuni.dialogue;

import com.sun.net.httpserver.HttpServer;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.variables.Variables;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class DialogueDisplayPlugin extends JavaPlugin implements Listener {
    private static final int MAXIMUM_LINES = 4;
    private static final int MAXIMUM_CHARACTERS_PER_LINE = 30;
    private static final int MAXIMUM_LINE_PIXELS = MAXIMUM_CHARACTERS_PER_LINE * 9;
    private static final int CHOICE_LINE_PIXELS = 190;
    private static final int MAXIMUM_PAGES = 30;
    private static final int DEFAULT_TEXT_SIZE = 62;
    private static final UUID SYSTEM_PUBLIC_OWNER = new UUID(0L, 0L);
    private static final double MINIMUM_UI_SCALE = 0.6;
    private static final double MAXIMUM_UI_SCALE = 1.4;
    private static final java.util.regex.Pattern VARIABLE_PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{(.+?)}}");
    private static final java.util.regex.Pattern SKRIPT_VARIABLE_PLACEHOLDER = java.util.regex.Pattern.compile("%\\{(.+?)}%");
    private static final java.util.regex.Pattern VARIABLE_ASSIGNMENT = java.util.regex.Pattern.compile("^(.+?)\\s*(\\+=|-=|\\*=|/=|=)\\s*(.*)$");
    private static final java.util.regex.Pattern VARIABLE_CHECK = java.util.regex.Pattern.compile("^([^!<>=]+?)\\s*(==|=|!=|>=|<=|>|<)\\s*(.*)$");
    private final Map<UUID, Dialogue> active = new HashMap<>();
    private final Map<UUID, String> awaitingChatInputs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> editorPage = new HashMap<>();
    private final Map<UUID, String> editorName = new HashMap<>();
    private final Map<UUID, Integer> editorChoice = new HashMap<>();
    private final Map<UUID, Integer> editorChoicePage = new HashMap<>();
    private final Map<UUID, Boolean> effectChoiceMode = new HashMap<>();
    private final Map<UUID, String> effectSection = new HashMap<>();
    private final Map<UUID, Boolean> conditionChoiceMode = new HashMap<>();
    private final Map<UUID, String> conditionSection = new HashMap<>();
    private final Map<UUID, Boolean> appearanceChoiceMode = new HashMap<>();
    private final Map<UUID, UUID> editorOwner = new HashMap<>();
    private final Map<UUID, String> choiceRootOverride = new HashMap<>();
    private final Map<UUID, ChoiceContext> choiceParent = new HashMap<>();
    private final Map<UUID, List<String>> editorLists = new HashMap<>();
    private final Map<UUID, List<String>> itemEditorLists = new HashMap<>();
    private final Map<UUID, String> itemEditorName = new HashMap<>();
    private BukkitTask tracker;
    private HttpServer packServer;
    private ExecutorService packExecutor;
    private CharacterRegistry characterRegistry;
    private DialogueWebApi webApi;
    private RpgDataStore dataStore;
    private Listener citizensNpc;
    private NamespacedKey hotbarItemKey, hotbarSlotKey, temporaryHandKey, uiScaleKey, skriptSyncKey;
    private boolean skriptBridgeReady, skriptSyncReady;

    @Override
    public void onEnable() {
        hotbarItemKey = new NamespacedKey(this, "dialogue_hotbar_item");
        hotbarSlotKey = new NamespacedKey(this, "dialogue_hotbar_slot");
        temporaryHandKey = new NamespacedKey(this, "temporary_hand");
        uiScaleKey = new NamespacedKey(this, "dialogue_ui_scale");
        skriptSyncKey = new NamespacedKey(this, "skript_sync_initialized");
        saveDefaultConfig();
        dataStore = new RpgDataStore(getDataFolder().toPath(), getLogger());
        try {
            dataStore.loadInto(getConfig());
            saveConfig();
        } catch (IOException error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not load RPGMaker player data", error);
        }
        characterRegistry = CharacterRegistry.load(this);
        webApi = DialogueWebApi.start(this, new DialogueCompatibilityService(this), characterRegistry);
        installBundledExamples();
        installPublicGuideDialogue();
        skriptBridgeReady = Bukkit.getPluginManager().isPluginEnabled("Skript");
        if (!skriptBridgeReady) getLogger().warning("Skript bridge is disabled: Skript is not installed.");
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            CitizensDialogueNpc bridge = new CitizensDialogueNpc(this);
            citizensNpc = bridge;
            Bukkit.getScheduler().runTaskLater(this, bridge::install, 40L);
        }
        else getLogger().warning("Citizens dialogue NPC is disabled: Citizens is not installed.");
        startPackServer();
        tracker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        Bukkit.getOnlinePlayers().forEach(player -> {
            restoreDialogueHotbar(player);
            syncRpgVariables(player);
        });
        Bukkit.getScheduler().runTaskLater(this, () -> {
            migrateStoredSkriptVariables();
            skriptSyncReady = true;
            Bukkit.getOnlinePlayers().forEach(this::syncRpgVariables);
        }, 100L);
    }

    @Override
    public void onDisable() {
        if (tracker != null) tracker.cancel();
        active.values().forEach(Dialogue::remove);
        active.clear();
        awaitingChatInputs.clear();
        if (webApi != null) webApi.stop();
        if (packServer != null) packServer.stop(0);
        if (packExecutor != null) packExecutor.shutdownNow();
    }

    @Override
    public synchronized void saveConfig() {
        if (dataStore == null) {
            super.saveConfig();
            return;
        }
        try {
            dataStore.save(getConfig());
        } catch (IOException error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not save RPGMaker data", error);
        }
    }

    @EventHandler
    public void onHeldSlot(PlayerItemHeldEvent event) {
        Dialogue dialogue = active.get(event.getPlayer().getUniqueId());
        if (dialogue == null) return;
        event.setCancelled(true);
        int choice = event.getNewSlot();
        if (dialogue.waitingForChoice && choice < dialogue.choices.size()) {
            Bukkit.getScheduler().runTask(this, () -> choose(event.getPlayer(), Integer.toString(choice + 1)));
        }
        Bukkit.getScheduler().runTask(this, () -> event.getPlayer().getInventory().setHeldItemSlot(8));
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && active.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && active.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { close(event.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        awaitingChatInputs.remove(event.getPlayer().getUniqueId());
        Dialogue dialogue = active.remove(event.getPlayer().getUniqueId());
        if (dialogue != null) dialogue.removeDisplays();
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(this, () -> restoreDialogueHotbar(event.getPlayer()));
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Dialogue dialogue = active.get(event.getPlayer().getUniqueId());
        if (dialogue == null || dialogue.editing || dialogue.waitingForChoice || dialogue.waitingForChat) return;
        event.setCancelled(true);
        if (dialogue.typed < dialogue.message.length()) return;
        if (dialogue.waitingForNext || dialogue.waitingForClose) advanceFromPage(dialogue, false);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        getLogger().info("Resource pack " + event.getStatus() + " for " + event.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true)
    public void onChatInput(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String variable = awaitingChatInputs.remove(player.getUniqueId());
        if (variable == null) return;
        event.setCancelled(true);
        String value = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        if (value.length() > 200) value = value.substring(0, 200);
        String captured = value;
        Bukkit.getScheduler().runTask(this, () -> {
            if (captured.isBlank()) {
                awaitingChatInputs.put(player.getUniqueId(), variable);
                player.sendMessage(Component.text("빈 값은 저장할 수 없습니다. 채팅에 다시 입력해 주세요.", NamedTextColor.RED));
                return;
            }
            setVariableValue(player, variable, captured);
            Dialogue dialogue = active.get(player.getUniqueId());
            if (dialogue != null) {
                dialogue.waitingForChat = false;
                player.sendActionBar(Component.text("입력이 저장되었습니다. Shift 키를 눌러 계속", NamedTextColor.GREEN));
            }
        });
    }

    @EventHandler
    public void onEditorSubmit(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        String action = event.getIdentifier().asString();
        Player player = connection.getPlayer();
        DialogResponseView response = event.getDialogResponseView();
        if (action.startsWith("dialoguedisplay:ui_scale_")) {
            String operation = action.substring("dialoguedisplay:ui_scale_".length());
            if (operation.equals("back")) Bukkit.getScheduler().runTask(this, () -> openQuickActions(player));
            else {
                double scale = switch (operation) {
                    case "down" -> playerUiScale(player) - 0.1;
                    case "up" -> playerUiScale(player) + 0.1;
                    default -> 1.0;
                };
                setPlayerUiScale(player, scale);
                Bukkit.getScheduler().runTask(this, () -> openPlayerSettings(player));
            }
            return;
        }
        if (action.equals("dialoguedisplay:variable_help_back")) {
            Bukkit.getScheduler().runTask(this, () -> openQuickActions(player));
            return;
        }
        if (action.equals("dialoguedisplay:variable_help_info")) return;
        if (action.equals("dialoguedisplay:discard_editor")) {
            Bukkit.getScheduler().runTask(this, () -> openEditorList(player));
            return;
        }
        if (action.equals("dialoguedisplay:back_quick_actions")) {
            Bukkit.getScheduler().runTask(this, () -> openQuickActions(player));
            return;
        }
        if (action.equals("dialoguedisplay:delete_current_dialogue")) {
            Bukkit.getScheduler().runTask(this, () -> openDeleteConfirmation(player, "대화문", "confirm_delete_dialogue", "cancel_delete_dialogue"));
            return;
        }
        if (action.equals("dialoguedisplay:confirm_delete_dialogue")) {
            String name = editorName.getOrDefault(player.getUniqueId(), "new_dialogue");
            String path = dialoguePath(player, name);
            if (getConfig().contains(path)) {
                if (isBuiltInExample(path)) dismissExample(player, name);
                getConfig().set(path, null);
                saveConfig();
                player.sendMessage(Component.text("대화 '" + name + "'을 삭제했습니다.", NamedTextColor.RED));
            }
            Bukkit.getScheduler().runTask(this, () -> openEditorList(player));
            return;
        }
        if (action.equals("dialoguedisplay:cancel_delete_dialogue")) {
            Bukkit.getScheduler().runTask(this, () -> openContentEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:discard_choice")) {
            Bukkit.getScheduler().runTask(this, () -> openChoiceList(player));
            return;
        }
        if (action.startsWith("dialoguedisplay:pick_")) {
            handleAppearancePick(player, action.substring("dialoguedisplay:pick_".length()));
            return;
        }
        if (List.of("dialoguedisplay:save_effect", "dialoguedisplay:back_effect").contains(action)) {
            boolean choiceMode = effectChoiceMode.getOrDefault(player.getUniqueId(), false);
            saveEffectEditor(player, response, choiceMode);
            Bukkit.getScheduler().runTask(this, () -> {
                if (action.equals("dialoguedisplay:save_effect"))
                    openEffectEditor(player, choiceMode, effectSection.getOrDefault(player.getUniqueId(), "ITEM"));
                else openSettingsHub(player, choiceMode);
            });
            return;
        }
        if (action.equals("dialoguedisplay:clear_effect_items")) {
            boolean choiceMode = effectChoiceMode.getOrDefault(player.getUniqueId(), false);
            String path = effectPath(player, choiceMode);
            for (String key : List.of("items", "take-items", "item", "amount", "take-item", "take-amount", "item-name", "item-color"))
                getConfig().set(path + "." + key, null);
            saveConfig();
            player.sendMessage(Component.text("이 대사의 지급·소모 아이템을 전부 삭제했습니다.", NamedTextColor.RED));
            Bukkit.getScheduler().runTask(this, () -> openEffectEditor(player, choiceMode, "ITEM"));
            return;
        }
        if (action.equals("dialoguedisplay:clear_condition_items")) {
            boolean choiceMode = conditionChoiceMode.getOrDefault(player.getUniqueId(), false);
            String path = conditionPath(player, choiceMode);
            for (String key : List.of("item-spec", "item", "amount", "name-match", "item-name"))
                getConfig().set(path + "." + key, null);
            String type = getConfig().getString(path + ".type", "NONE");
            if (type.equals("ITEM")) getConfig().set(path + ".type", "NONE");
            else if (type.equals("BOTH") || type.equals("ANY")) getConfig().set(path + ".type", "VARIABLE");
            saveConfig();
            player.sendMessage(Component.text("아이템 표시 조건을 전부 삭제했습니다.", NamedTextColor.RED));
            Bukkit.getScheduler().runTask(this, () -> openConditionEditor(player, choiceMode, "ITEM"));
            return;
        }
        if (action.startsWith("dialoguedisplay:return_")) {
            boolean choiceMode = effectChoiceMode.getOrDefault(player.getUniqueId(), false);
            Bukkit.getScheduler().runTask(this, () -> {
                switch (action) {
                    case "dialoguedisplay:return_none" -> {
                        String path = effectPath(player, choiceMode);
                        getConfig().set(path + ".return-mode", "NONE");
                        getConfig().set(path + ".return-target", null);
                        saveConfig();
                        openReturnEditor(player, choiceMode);
                    }
                    case "dialoguedisplay:return_pages" -> openReturnTargetPicker(player, choiceMode, "PAGE");
                    case "dialoguedisplay:return_choices" -> openReturnTargetPicker(player, choiceMode, "CHOICE");
                    case "dialoguedisplay:return_save" -> {
                        String target = response.getText("return_target");
                        if (target != null && !target.isBlank()) {
                            String path = effectPath(player, choiceMode);
                            getConfig().set(path + ".return-mode", "TARGET");
                            getConfig().set(path + ".return-target", target);
                            saveConfig();
                        }
                        openReturnEditor(player, choiceMode);
                    }
                    case "dialoguedisplay:return_back" -> openSettingsHub(player, choiceMode);
                    default -> openReturnEditor(player, choiceMode);
                }
            });
            return;
        }
        if (List.of("dialoguedisplay:save_condition", "dialoguedisplay:back_condition").contains(action)) {
            boolean choiceMode = conditionChoiceMode.getOrDefault(player.getUniqueId(), false);
            saveConditionEditor(player, response, choiceMode);
            Bukkit.getScheduler().runTask(this, () -> {
                if (action.equals("dialoguedisplay:save_condition"))
                    openConditionEditor(player, choiceMode, conditionSection.getOrDefault(player.getUniqueId(), "MODE"));
                else openConditionHub(player, choiceMode);
            });
            return;
        }
        if (action.startsWith("dialoguedisplay:settings_")) {
            boolean choiceMode = effectChoiceMode.getOrDefault(player.getUniqueId(), false);
            Bukkit.getScheduler().runTask(this, () -> {
                switch (action) {
                    case "dialoguedisplay:settings_item" -> openEffectEditor(player, choiceMode, "ITEM");
                    case "dialoguedisplay:settings_variable" -> openEffectEditor(player, choiceMode, "VARIABLE");
                    case "dialoguedisplay:settings_sound" -> openEffectEditor(player, choiceMode, "SOUND");
                    case "dialoguedisplay:settings_message" -> openEffectEditor(player, choiceMode, "MESSAGE");
                    case "dialoguedisplay:settings_return" -> openEffectEditor(player, choiceMode, "RETURN");
                    case "dialoguedisplay:settings_command" -> openEffectEditor(player, choiceMode, "COMMAND");
                    case "dialoguedisplay:settings_condition" -> openConditionHub(player, choiceMode);
                    case "dialoguedisplay:settings_flow" -> { if (!choiceMode) openFlowEditor(player); else openSettingsHub(player, true); }
                    default -> { if (choiceMode) openChoiceEditor(player); else openContentEditor(player); }
                }
            });
            return;
        }
        if (action.startsWith("dialoguedisplay:condition_")) {
            boolean choiceMode = conditionChoiceMode.getOrDefault(player.getUniqueId(), false);
            String section = action.substring(action.lastIndexOf('_') + 1).toUpperCase(java.util.Locale.ROOT);
            Bukkit.getScheduler().runTask(this, () -> {
                if (section.equals("BACK")) openSettingsHub(player, choiceMode);
                else openConditionEditor(player, choiceMode, section);
            });
            return;
        }
        if (List.of("dialoguedisplay:save_flow", "dialoguedisplay:back_flow").contains(action)) {
            saveFlowEditor(player, response);
            Bukkit.getScheduler().runTask(this, () -> {
                if (action.equals("dialoguedisplay:save_flow")) openFlowEditor(player);
                else openSettingsHub(player, false);
            });
            return;
        }
        if (action.equals("dialoguedisplay:back_content")) {
            ChoiceContext parent = choiceParent.remove(player.getUniqueId());
            choiceRootOverride.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(this, () -> {
                if (parent == null) openContentEditor(player);
                else {
                    editorChoice.put(player.getUniqueId(), parent.choice());
                    editorChoicePage.put(player.getUniqueId(), parent.page());
                    openChoiceEditor(player);
                }
            });
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_open")) {
            Bukkit.getScheduler().runTask(this, () -> openItemEditorList(player));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_custom_list")) {
            Bukkit.getScheduler().runTask(this, () -> openItemCategoryList(player, true));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_special_list")) {
            Bukkit.getScheduler().runTask(this, () -> openItemCategoryList(player, false));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_section")) return;
        if (action.equals("dialoguedisplay:item_editor_new")) {
            itemEditorName.put(player.getUniqueId(), "new_item");
            Bukkit.getScheduler().runTask(this, () -> openItemEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_capture_new")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType().isAir()) player.sendMessage(Component.text("손에 아이템을 들고 다시 눌러 주세요.", NamedTextColor.RED));
            else {
                itemEditorName.put(player.getUniqueId(), "new_item");
                Bukkit.getScheduler().runTask(this, () -> openHeldItemNameDialog(player));
            }
            return;
        }
        if (action.startsWith("dialoguedisplay:item_editor_edit_")) {
            openListedItemEditor(player, action, false);
            return;
        }
        if (action.startsWith("dialoguedisplay:item_editor_view_")) {
            openListedCapturedItem(player, action);
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_delete_current")) {
            Bukkit.getScheduler().runTask(this, () -> openDeleteConfirmation(player, "아이템", "confirm_delete_item", "cancel_delete_item"));
            return;
        }
        if (action.equals("dialoguedisplay:confirm_delete_item")) {
            String key = itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
            String path = customItemRoot(player.getUniqueId()) + "." + key;
            boolean captured = getConfig().contains(path + ".item-bytes");
            getConfig().set(path, null);
            saveConfig();
            player.sendMessage(Component.text("아이템 '" + key + "'을 삭제했습니다.", NamedTextColor.RED));
            Bukkit.getScheduler().runTask(this, () -> openItemCategoryList(player, captured));
            return;
        }
        if (action.equals("dialoguedisplay:cancel_delete_item")) {
            String path = customItemRoot(player.getUniqueId()) + "." + itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
            Bukkit.getScheduler().runTask(this, () -> {
                if (getConfig().contains(path + ".item-bytes")) openCapturedItemView(player);
                else openItemEditor(player);
            });
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_back_list")) {
            String path = customItemRoot(player.getUniqueId()) + "." + itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
            boolean captured = getConfig().contains(path + ".item-bytes");
            Bukkit.getScheduler().runTask(this, () -> openItemCategoryList(player, captured));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_capture")) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType().isAir()) player.sendMessage(Component.text("손에 아이템을 들고 다시 눌러 주세요.", NamedTextColor.RED));
            else Bukkit.getScheduler().runTask(this, () -> openHeldItemNameDialog(player));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_capture_save")) {
            String title = response.getText("captured_item_key");
            boolean saved = captureHeldItem(player, title);
            Bukkit.getScheduler().runTask(this, () -> {
                if (saved) openItemCategoryList(player, true);
                else openHeldItemNameDialog(player);
            });
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_capture_cancel")) {
            Bukkit.getScheduler().runTask(this, () -> openItemEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:item_editor_save")) {
            saveItemEditor(player, response);
            Bukkit.getScheduler().runTask(this, () -> openItemEditor(player));
            return;
        }
        if (action.startsWith("dialoguedisplay:choice_edit_")) {
            try {
                editorChoice.put(player.getUniqueId(), Integer.parseInt(action.substring(action.lastIndexOf('_') + 1)));
                editorChoicePage.put(player.getUniqueId(), 0);
                Bukkit.getScheduler().runTask(this, () -> openChoiceEditor(player));
            } catch (NumberFormatException ignored) { }
            return;
        }
        if (action.equals("dialoguedisplay:choice_new")) {
            int next = Math.min(8, getConfig().getInt(choicePath(player) + ".choice-count", 0) + 1);
            editorChoice.put(player.getUniqueId(), next);
            editorChoicePage.put(player.getUniqueId(), 0);
            Bukkit.getScheduler().runTask(this, () -> openChoiceEditor(player));
            return;
        }
        if (action.startsWith("dialoguedisplay:editor_load_")) {
            try {
                int index = Integer.parseInt(action.substring(action.lastIndexOf('_') + 1));
                List<String> names = editorLists.getOrDefault(player.getUniqueId(), List.of());
                if (index >= 0 && index < names.size()) {
                    prepareEditor(player, names.get(index));
                    Bukkit.getScheduler().runTask(this, () -> openContentEditor(player));
                }
            } catch (NumberFormatException ignored) { }
            return;
        }
        if (action.equals("dialoguedisplay:delete_choice")) {
            Bukkit.getScheduler().runTask(this, () -> openDeleteConfirmation(player, "선택지", "confirm_delete_choice", "cancel_delete_choice"));
            return;
        }
        if (action.equals("dialoguedisplay:confirm_delete_choice")) {
            deleteChoice(player, editorChoice.getOrDefault(player.getUniqueId(), 1));
            Bukkit.getScheduler().runTask(this, () -> openChoiceList(player));
            return;
        }
        if (action.equals("dialoguedisplay:cancel_delete_choice")) {
            Bukkit.getScheduler().runTask(this, () -> openChoiceEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:delete_current_page")) {
            Bukkit.getScheduler().runTask(this, () -> openDeleteConfirmation(player, "현재 대사 페이지", "confirm_delete_page", "cancel_delete_page"));
            return;
        }
        if (action.equals("dialoguedisplay:confirm_delete_page")) {
            deleteEditorPage(player);
            Bukkit.getScheduler().runTask(this, () -> openContentEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:cancel_delete_page")) {
            Bukkit.getScheduler().runTask(this, () -> openContentEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:delete_choice_page")) {
            Bukkit.getScheduler().runTask(this, () -> openDeleteConfirmation(player, "현재 선택지 후속 페이지", "confirm_delete_choice_page", "cancel_delete_choice_page"));
            return;
        }
        if (action.equals("dialoguedisplay:confirm_delete_choice_page")) {
            deleteChoicePage(player);
            Bukkit.getScheduler().runTask(this, () -> openChoiceEditor(player));
            return;
        }
        if (action.equals("dialoguedisplay:cancel_delete_choice_page")) {
            Bukkit.getScheduler().runTask(this, () -> openChoiceEditor(player));
            return;
        }
        if (List.of("dialoguedisplay:save_choice", "dialoguedisplay:add_next_choice",
                "dialoguedisplay:choice_page_previous", "dialoguedisplay:choice_page_next",
                "dialoguedisplay:choice_page_add", "dialoguedisplay:choice_settings",
                "dialoguedisplay:choice_toggle_portrait", "dialoguedisplay:choice_toggle_speaker",
                "dialoguedisplay:choice_nested_choices",
                "dialoguedisplay:choice_appearance_character", "dialoguedisplay:choice_appearance_gender",
                "dialoguedisplay:choice_appearance_expression",
                "dialoguedisplay:back_choice_list").contains(action)) {
            int index = editorChoice.getOrDefault(player.getUniqueId(), 1);
            String path = choicePath(player);
            getConfig().set(path + ".choice-" + index, limitText(response.getText("choice"), 10));
            saveChoiceResponse(player, index, readLines(response));
            saveChoicePortrait(player, response, index);
            getConfig().set(path + ".end-" + index, "END".equals(response.getText("choice_flow")));
            getConfig().set(path + ".target-page-" + index,
                    safePageNumber(response.getText("choice_target_page"), editorMessages(player).size()));
            getConfig().set(path + ".speaker-" + index, TextWidthRules.limitVisible(response.getText("choice_speaker"), 10));
            int responsePage = editorChoicePage.getOrDefault(player.getUniqueId(), 0);
            if (action.equals("dialoguedisplay:choice_toggle_portrait")) {
                String visiblePath = path + ".response-show-portraits-" + index + "." + responsePage;
                getConfig().set(visiblePath, !getConfig().getBoolean(visiblePath, true));
            }
            if (action.equals("dialoguedisplay:choice_toggle_speaker")) {
                String visiblePath = path + ".response-show-speakers-" + index + "." + responsePage;
                boolean fallback = getConfig().getBoolean(path + ".response-show-portraits-" + index + "." + responsePage, true);
                getConfig().set(visiblePath, !getConfig().getBoolean(visiblePath, fallback));
            }
            getConfig().set(path + ".choice-count", Math.max(index, getConfig().getInt(path + ".choice-count", 0)));
            saveConfig();
            Bukkit.getScheduler().runTask(this, () -> {
                if (action.equals("dialoguedisplay:add_next_choice") && index < 8) {
                    editorChoice.put(player.getUniqueId(), index + 1);
                    editorChoicePage.put(player.getUniqueId(), 0);
                    openChoiceEditor(player);
                } else if (action.equals("dialoguedisplay:choice_page_previous")) {
                    editorChoicePage.compute(player.getUniqueId(), (id, page) -> Math.max(0, (page == null ? 0 : page) - 1));
                    openChoiceEditor(player);
                } else if (action.equals("dialoguedisplay:choice_page_next")) {
                    int last = choiceResponsePages(player, index).size() - 1;
                    editorChoicePage.compute(player.getUniqueId(), (id, page) -> Math.min(last, (page == null ? 0 : page) + 1));
                    openChoiceEditor(player);
                } else if (action.equals("dialoguedisplay:choice_page_add")) {
                    List<String> pages = choiceResponsePages(player, index);
                    if (pages.size() >= MAXIMUM_PAGES) { openChoiceEditor(player); return; }
                    pages.add("");
                    getConfig().set(path + ".response-pages-" + index, pages);
                    saveConfig();
                    editorChoicePage.put(player.getUniqueId(), pages.size() - 1);
                    openChoiceEditor(player);
                } else if (action.equals("dialoguedisplay:choice_settings")) openSettingsHub(player, true);
                else if (action.equals("dialoguedisplay:choice_toggle_portrait") || action.equals("dialoguedisplay:choice_toggle_speaker"))
                    openChoiceEditor(player);
                else if (action.startsWith("dialoguedisplay:choice_appearance_"))
                    openAppearancePicker(player, true, action.substring("dialoguedisplay:choice_appearance_".length()));
                else if (action.equals("dialoguedisplay:choice_nested_choices")) {
                    choiceParent.put(player.getUniqueId(), new ChoiceContext(path, index,
                            editorChoicePage.getOrDefault(player.getUniqueId(), 0)));
                    choiceRootOverride.put(player.getUniqueId(), path + ".response-page-choices-" + index + "."
                            + editorChoicePage.getOrDefault(player.getUniqueId(), 0));
                    openChoiceList(player);
                }
                else if (action.equals("dialoguedisplay:back_choice_list")) openChoiceList(player);
                else showNamed(player, editorName.get(player.getUniqueId()));
            });
            return;
        }
        if (!List.of("dialoguedisplay:save_editor", "dialoguedisplay:preview_editor", "dialoguedisplay:previous_dialogue",
                 "dialoguedisplay:next_dialogue", "dialoguedisplay:add_choice",
                 "dialoguedisplay:view_choices", "dialoguedisplay:page_settings",
                 "dialoguedisplay:toggle_portrait", "dialoguedisplay:toggle_speaker",
                 "dialoguedisplay:page_appearance_character", "dialoguedisplay:page_appearance_gender",
                 "dialoguedisplay:page_appearance_expression").contains(action)) return;
        String title = response.getText("dialogue_name");
        String currentName = editorName.getOrDefault(player.getUniqueId(), "default");
        String name = currentName.equals("new_dialogue") ? sanitizeName(title) : currentName;
        editorName.put(player.getUniqueId(), name);
        String path = editorPath(player);
        getConfig().set(path + ".title", title == null || title.isBlank() ? name : title.strip());
        getConfig().set(path + ".page-speakers." + editorPage.getOrDefault(player.getUniqueId(), 0),
                TextWidthRules.limitVisible(response.getText("speaker"), 10));
        if (action.equals("dialoguedisplay:toggle_portrait"))
            getConfig().set(path + ".page-show-portraits." + editorPage.getOrDefault(player.getUniqueId(), 0),
                    !getConfig().getBoolean(path + ".page-show-portraits." + editorPage.getOrDefault(player.getUniqueId(), 0),
                            getConfig().getBoolean(path + ".show-portrait", true)));
        if (action.equals("dialoguedisplay:toggle_portrait")) getConfig().set(path + ".show-portrait", true);
        if (action.equals("dialoguedisplay:toggle_speaker")) {
            int page = editorPage.getOrDefault(player.getUniqueId(), 0);
            boolean fallback = getConfig().getBoolean(path + ".page-show-portraits." + page,
                    getConfig().getBoolean(path + ".show-portrait", true));
            getConfig().set(path + ".page-show-speakers." + page,
                    !getConfig().getBoolean(path + ".page-show-speakers." + page, fallback));
        }
        saveEditorMessage(player, readLines(response), action.equals("dialoguedisplay:next_dialogue"));
        String selectedCharacter = response.getText("character");
        if (selectedCharacter != null) {
            String portrait = resolvePortrait(selectedCharacter, response.getText("gender"));
            getConfig().set(path + ".page-portraits." + editorPage.getOrDefault(player.getUniqueId(), 0), portrait);
            getConfig().set(path + ".page-expressions." + editorPage.getOrDefault(player.getUniqueId(), 0),
                    normalizeExpression(portrait, response.getText("expression")));
        }
        saveConfig();
        Bukkit.getScheduler().runTask(this, () -> {
            if (action.equals("dialoguedisplay:page_settings")) openSettingsHub(player, false);
            else if (action.startsWith("dialoguedisplay:page_appearance_"))
                openAppearancePicker(player, false, action.substring("dialoguedisplay:page_appearance_".length()));
            else if (action.equals("dialoguedisplay:view_choices")) {
                choiceRootOverride.remove(player.getUniqueId());
                choiceParent.remove(player.getUniqueId());
                openChoiceList(player);
            }
            else if (action.equals("dialoguedisplay:toggle_portrait") || action.equals("dialoguedisplay:toggle_speaker")) openContentEditor(player);
            else if (action.equals("dialoguedisplay:previous_dialogue")) {
                editorPage.compute(player.getUniqueId(), (id, page) -> Math.max(0, (page == null ? 0 : page) - 1));
                openContentEditor(player);
            }
            else if (action.equals("dialoguedisplay:next_dialogue")) {
                List<String> pages = editorMessages(player);
                int page = editorPage.getOrDefault(player.getUniqueId(), 0);
                if (page == pages.size() - 1 && pages.size() < MAXIMUM_PAGES) {
                    pages.add("");
                    getConfig().set(path + ".message-pages", pages);
                    saveConfig();
                }
                editorPage.put(player.getUniqueId(), Math.min(pages.size() - 1, page + 1));
                openContentEditor(player);
            }
            else if (action.equals("dialoguedisplay:add_choice")) {
                choiceRootOverride.remove(player.getUniqueId());
                choiceParent.remove(player.getUniqueId());
                int next = Math.min(8, getConfig().getInt(choicePath(player) + ".choice-count", 0) + 1);
                editorChoice.put(player.getUniqueId(), next);
                editorChoicePage.put(player.getUniqueId(), 0);
                openChoiceEditor(player);
            }
            else if (action.equals("dialoguedisplay:save_editor")) {
                player.sendMessage(Component.text("대화문을 저장했습니다.", NamedTextColor.GREEN));
                openContentEditor(player);
            } else showNamed(player, name);
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("variable")) return handleVariableCommand(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("게임 안에서 실행하세요.", NamedTextColor.RED));
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("settings")) {
            openPlayerSettings(player);
            return true;
        }
        if (sub.equals("web")) {
            if (webApi == null) {
                player.sendMessage(Component.text("웹 에디터 API가 비활성화되어 있습니다.", NamedTextColor.RED));
                return true;
            }
            String link = webApi.issuePlayerLink(player);
            player.sendMessage(Component.text("[웹 에디터 열기]", NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.openUrl(link)));
            player.sendMessage(Component.text(player.getName() + " 계정으로 자동 연결됩니다. 이 링크는 다른 사람과 공유하지 마세요.", NamedTextColor.GRAY));
            return true;
        }
        if (List.of("edit", "edit2", "edit3", "edit4", "adjust", "save", "show", "npc").contains(sub)
                || (sub.equals("close") && args.length > 1)) {
            if (!player.hasPermission("rpgmaker.admin")) {
                player.sendMessage(Component.text("이 기능은 OP만 사용할 수 있습니다.", NamedTextColor.RED));
                return true;
            }
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("editor")) {
            editorOwner.remove(player.getUniqueId());
            if (args.length == 1) openQuickActions(player);
            else if (args[1].equalsIgnoreCase("help")) sendEditorHelp(player);
            else if (args[1].equalsIgnoreCase("variables")) openVariableHelp(player);
            else if (args[1].equalsIgnoreCase("list")) openEditorList(player);
            else {
                prepareEditor(player, args[1]);
                openContentEditor(player);
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("items")) {
            openItemEditorList(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("examples") && args[1].equalsIgnoreCase("restore")) {
            String dismissedPath = "dismissed-examples." + player.getUniqueId();
            java.util.ArrayList<String> dismissed = new java.util.ArrayList<>(getConfig().getStringList(dismissedPath));
            dismissed.removeAll(List.of(sanitizeName("달빛_아래_피어난_약속"), sanitizeName("초보_상점_이용법")));
            getConfig().set(dismissedPath, dismissed);
            ensureExamples(player);
            saveConfig();
            player.sendMessage(Component.text("삭제한 연애·상점 예제를 최신 버전으로 복구했습니다.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("load")) {
            editorOwner.remove(player.getUniqueId());
            prepareEditor(player, args[1]);
            openContentEditor(player);
            return true;
        }
        if (args.length == 1 && List.of("edit", "edit2", "edit3", "edit4").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            String mode = args[0].toLowerCase(java.util.Locale.ROOT);
            boolean showPortrait = mode.equals("edit") || mode.equals("edit4");
            boolean showSpeaker = mode.equals("edit") || mode.equals("edit3");
            show(player, "화자 이름 예시", "첫 번째 줄 예시\n두 번째 줄 예시\n세 번째 줄 예시\n네 번째 줄 예시", List.of(), "NORTH",
                    getConfig().getDouble("distance", 1.8), showPortrait, showSpeaker);
            Dialogue dialogue = active.get(player.getUniqueId());
            dialogue.editing = true;
            dialogue.typed = dialogue.message.length();
            dialogue.expiresAt = Integer.MAX_VALUE;
            render(dialogue);
            dialogue.choiceDisplay.text(editorChoicePreview());
            updatePortrait(dialogue);
            applyScales(dialogue);
            editorControls(player);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("adjust")) {
            if (!active.containsKey(player.getUniqueId()) || !active.get(player.getUniqueId()).editing) return true;
            String key = args[1];
            if (!List.of("vertical-offset", "frame-x-offset", "frame-scale", "frame-scale-x", "frame-scale-y", "portrait-x-offset",
                    "portrait-vertical-offset", "portrait-scale", "text-x-offset", "text-vertical-offset",
                    "text-scale", "speaker-x-offset", "speaker-vertical-offset", "speaker-scale",
                    "choice-x-offset", "choice-vertical-offset", "choice-scale", "choice-frame-x-offset",
                    "choice-frame-vertical-offset", "choice-frame-scale", "choice-frame-scale-x", "choice-frame-scale-y").contains(key)) return true;
            try {
                Dialogue edited = active.get(player.getUniqueId());
                String prefix = layoutPrefix(edited.showPortrait, edited.showSpeaker);
                double delta = Double.parseDouble(args[2]);
                if (key.equals("choice-frame-scale")) {
                    for (String axis : List.of("x", "y")) {
                        String configKey = prefix + "choice-frame-scale-" + axis;
                        getConfig().set(configKey, getConfig().getDouble(configKey, choiceFrameScaleDefault(edited, axis)) + delta);
                    }
                } else {
                    String configKey = prefix + key;
                    double fallback = switch (key) {
                        case "choice-frame-x-offset" -> layout(edited, "choice-x-offset", -0.06);
                        case "choice-frame-vertical-offset" -> layout(edited, "choice-vertical-offset", -0.20);
                        case "choice-frame-scale-x" -> choiceFrameScaleDefault(edited, "x");
                        case "choice-frame-scale-y" -> choiceFrameScaleDefault(edited, "y");
                        case "text-x-offset" -> -0.06;
                        case "text-vertical-offset" -> 0.05;
                        case "text-scale" -> DEFAULT_TEXT_SIZE / 100.0;
                        default -> lineLayoutDefault(edited, key);
                    };
                    getConfig().set(configKey, getConfig().getDouble(configKey, fallback) + delta);
                }
                applyScales(edited);
                saveConfig();
                editorControls(player);
            } catch (NumberFormatException ignored) { }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("save")) {
            saveConfig();
            player.sendMessage(Component.text("다이얼로그 배치를 저장했습니다.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            showNamed(player, sanitizeName(args[1]));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("public")) {
            List<String> names = publicDialogueNames();
            sender.sendMessage(Component.text("공용 대화문: " + (names.isEmpty() ? "없음" : String.join(", ", names)), NamedTextColor.AQUA));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("publish")) {
            String name = sanitizeName(args[1]);
            String source = "player-dialogues." + player.getUniqueId() + "." + name;
            if (!getConfig().contains(source)) {
                sender.sendMessage(Component.text("내 대화문 '" + name + "'을 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            String destination = "public-dialogues." + name;
            String owner = getConfig().getString(destination + ".public-owner", "");
            if (!owner.isBlank() && !owner.equals(player.getUniqueId().toString())) {
                sender.sendMessage(Component.text("다른 유저가 올린 공용 대화문은 수정할 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            copySection(source, destination);
            getConfig().set(destination + ".public-owner", player.getUniqueId().toString());
            getConfig().set(destination + ".public-by", player.getName());
            saveConfig();
            sender.sendMessage(Component.text("'" + name + "'을 공용 대화문으로 저장했습니다.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("npc") && citizensNpc instanceof CitizensDialogueNpc bridge) {
            bridge.handleCommand(player, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("share")) {
            String name = sanitizeName(args[1]);
            String source = dialoguePath(player, name);
            if (!getConfig().contains(source)) {
                sender.sendMessage(Component.text("대화문 '" + name + "'을 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            if (isBuiltInExample(source)) {
                sender.sendMessage(Component.text("기본 예제는 공유할 수 없습니다. 복사본을 만든 뒤 공유하세요.", NamedTextColor.RED));
                return true;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            String sharedPath = "shared-dialogues." + token;
            copySection(source, sharedPath);
            getConfig().set(sharedPath + ".shared-by", player.getName());
            getConfig().set(sharedPath + ".shared-name", name);
            saveConfig();
            Component link = Component.text("[대화문 보기]", NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/rpgmaker shared " + token));
            Bukkit.broadcast(Component.text(player.getName() + "님이 '" + name + "' 대화문을 공유했습니다. ", NamedTextColor.YELLOW)
                    .append(link));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("shared")) {
            String path = "shared-dialogues." + args[1].replaceAll("[^a-fA-F0-9]", "");
            if (!getConfig().contains(path)) {
                player.sendMessage(Component.text("공유된 대화문을 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            showPath(player, path);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            String name = sanitizeName(args[1]);
            String path = "player-dialogues." + player.getUniqueId() + "." + name;
            if (getConfig().contains(path)) {
                if (isBuiltInExample(path)) dismissExample(player, name);
                getConfig().set(path, null);
                saveConfig();
                sender.sendMessage(Component.text("대화문 '" + name + "'을 삭제했습니다.", NamedTextColor.RED));
            } else sender.sendMessage(Component.text("대화문을 찾을 수 없습니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            List<String> names = dialogueNames(player);
            sender.sendMessage(Component.text("내 대화문: " + (names.isEmpty() ? "없음" : String.join(", ", names)), NamedTextColor.AQUA));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("choose")) {
            choose(player, args[1]);
            return true;
        }
        if (args[0].equalsIgnoreCase("show")) {
            if (args.length != 4) {
                sender.sendMessage(Component.text("사용법: /rpgmaker show <보여줄 플레이어> <저장 주인> <대화명>", NamedTextColor.RED));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("보여줄 플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            var owner = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                    .filter(candidate -> candidate.getName() != null && candidate.getName().equalsIgnoreCase(args[2]))
                    .findFirst().orElse(null);
            if (owner == null) {
                sender.sendMessage(Component.text("저장 주인을 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            String path = "player-dialogues." + owner.getUniqueId() + "." + sanitizeName(args[3]);
            if (!getConfig().contains(path)) {
                sender.sendMessage(Component.text("해당 대화문을 찾을 수 없습니다.", NamedTextColor.RED));
                return true;
            }
            showPath(target, path);
            sender.sendMessage(Component.text(target.getName() + "에게 " + owner.getName() + "의 '" + args[3] + "' 대화문을 표시했습니다.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("close")) {
            Player target = args.length == 1 ? player :
                    args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
            if (target != null) close(target);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin") && player.hasPermission("rpgmaker.admin")) {
            if (args[1].equalsIgnoreCase("deleteall") && args.length == 3 && args[2].equals("CONFIRM")) {
                var players = getConfig().getConfigurationSection("player-dialogues");
                if (players != null) for (String ownerId : players.getKeys(false)) {
                    String root = "player-dialogues." + ownerId;
                    var dialogues = getConfig().getConfigurationSection(root);
                    if (dialogues != null) for (String name : dialogues.getKeys(false))
                        if (!isBuiltInExample(root + "." + name)) getConfig().set(root + "." + name, null);
                }
                getConfig().set("shared-dialogues", null);
                saveConfig();
                sender.sendMessage(Component.text("모든 플레이어 대화문과 공유본을 삭제했습니다.", NamedTextColor.RED));
                return true;
            }
            if (args.length >= 3) {
                var owner = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                        .filter(candidate -> candidate.getName() != null && candidate.getName().equalsIgnoreCase(args[2]))
                        .findFirst().orElse(null);
                if (owner == null) {
                    sender.sendMessage(Component.text("플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
                    return true;
                }
                String root = "player-dialogues." + owner.getUniqueId();
                var section = getConfig().getConfigurationSection(root);
                if (args[1].equalsIgnoreCase("list")) {
                    List<String> names = adminDialogueNames(root);
                    sender.sendMessage(Component.text(args[2] + "의 대화문: " + (names.isEmpty() ? "없음" : String.join(", ", names)), NamedTextColor.AQUA));
                    return true;
                }
                if (args.length == 4) {
                    String path = root + "." + sanitizeName(args[3]);
                    if (isBuiltInExample(path)) {
                        sender.sendMessage(Component.text("기본 예제는 관리자 명령 대상에서 제외됩니다.", NamedTextColor.RED));
                        return true;
                    }
                    if (args[1].equalsIgnoreCase("play")) { showPath(player, path); return true; }
                    if (args[1].equalsIgnoreCase("edit")) {
                        if (!getConfig().contains(path)) {
                            sender.sendMessage(Component.text("대화문을 찾을 수 없습니다.", NamedTextColor.RED));
                            return true;
                        }
                        editorOwner.put(player.getUniqueId(), owner.getUniqueId());
                        prepareEditor(player, args[3]);
                        openContentEditor(player);
                        return true;
                    }
                    if (args[1].equalsIgnoreCase("delete")) {
                        getConfig().set(path, null); saveConfig();
                        sender.sendMessage(Component.text("대화문을 삭제했습니다.", NamedTextColor.RED)); return true;
                    }
                }
            }
            sender.sendMessage(Component.text("/rpgmaker admin list|play|edit|delete <플레이어> [대화명] · deleteall CONFIRM", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("알 수 없는 명령어입니다. /rpgmaker help를 확인하세요.", NamedTextColor.RED));
        return true;
    }

    private boolean handleVariableCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("rpgmaker.admin")) {
            sender.sendMessage(Component.text("관리자 권한이 필요합니다.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("/rpgmaker variable <get|set|delete> <플레이어> <변수> [값]", NamedTextColor.YELLOW));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Component.text("온라인 플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
            return true;
        }
        String name = args[3].strip();
        if (name.isBlank()) {
            sender.sendMessage(Component.text("변수 이름이 비어 있습니다.", NamedTextColor.RED));
            return true;
        }
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "get" -> sender.sendMessage(Component.text(name + " = " + variableValue(target, name), NamedTextColor.AQUA));
            case "set" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text("설정할 값이 필요합니다.", NamedTextColor.RED));
                    return true;
                }
                setVariableValue(target, name, String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)));
                sender.sendMessage(Component.text("변수를 저장했습니다.", NamedTextColor.GREEN));
            }
            case "delete" -> {
                deleteVariableValue(target, name);
                sender.sendMessage(Component.text("변수를 삭제했습니다.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("get, set, delete 중 하나를 사용하세요.", NamedTextColor.YELLOW));
        }
        return true;
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        if (!event.getInput().isJump()) return;
        Dialogue dialogue = active.get(event.getPlayer().getUniqueId());
        if (dialogue == null || dialogue.editing || dialogue.typed >= dialogue.message.length()) return;
        dialogue.typed = dialogue.message.length();
        render(dialogue);
        finishPage(dialogue);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        restoreDialogueHotbar(player);
        syncRpgVariables(player);
        if (!player.isOp()) player.setGameMode(GameMode.ADVENTURE);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            ensureExamples(player);
            player.sendMessage(Component.text("[RPGMaker] /rpgmaker help · /rpgmaker editor · /rpgmaker settings · /rpgmaker web", NamedTextColor.GOLD));
            player.sendMessage(Component.text("G키: 대화문·아이템 편집 및 새 대화문 만들기", NamedTextColor.GREEN));
            player.sendMessage(Component.text("대화 중 Space: 대화문 스킵 · Shift: 다음 대사", NamedTextColor.AQUA));
        }, 30L);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission("rpgmaker.admin");
        List<String> commands = admin
                ? List.of("help", "editor", "settings", "items", "examples", "load", "play", "delete", "share", "publish", "public", "list", "edit", "edit2", "edit3", "edit4", "save", "show", "close", "npc", "admin")
                : List.of("help", "editor", "settings", "items", "examples", "load", "play", "delete", "share", "publish", "public", "list", "close");
        if (args.length == 1) return complete(args[0], commands);
        if (args.length == 2 && args[0].equalsIgnoreCase("editor")) return complete(args[1], List.of("help", "list", "variables"));
        if (args.length == 2 && args[0].equalsIgnoreCase("examples")) return complete(args[1], List.of("restore"));
        if (args.length == 2 && sender instanceof Player player
                && List.of("load", "play", "delete", "share", "publish").contains(args[0].toLowerCase())) {
            List<String> names = dialogueNames(player);
            if (args[0].equalsIgnoreCase("share"))
                names = names.stream().filter(name -> !isBuiltInExample(personalDialoguePath(player, name))).toList();
            if (args[0].equalsIgnoreCase("play"))
                names = java.util.stream.Stream.concat(names.stream(), publicDialogueNames().stream()).distinct().toList();
            return complete(args[1], names);
        }
        if (admin && args[0].equalsIgnoreCase("npc")) {
            if (args.length == 2) return complete(args[1], List.of("bind", "unbind"));
            if (args.length == 4 && args[1].equalsIgnoreCase("bind")) return complete(args[3], publicDialogueNames());
        }
        if (args.length == 2 && admin && List.of("show", "close").contains(args[0].toLowerCase()))
            return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        if (admin && args[0].equalsIgnoreCase("show")) {
            if (args.length == 3)
                return complete(args[2], java.util.Arrays.stream(Bukkit.getOfflinePlayers()).map(org.bukkit.OfflinePlayer::getName).filter(java.util.Objects::nonNull).toList());
            if (args.length == 4) {
                var owner = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                        .filter(candidate -> candidate.getName() != null && candidate.getName().equalsIgnoreCase(args[2]))
                        .findFirst().orElse(null);
                if (owner == null) return List.of();
                return complete(args[3], adminDialogueNames("player-dialogues." + owner.getUniqueId()));
            }
        }
        if (admin && args[0].equalsIgnoreCase("admin")) {
            if (args.length == 2) return complete(args[1], List.of("list", "play", "edit", "delete", "deleteall"));
            if (args.length == 3 && !args[1].equalsIgnoreCase("deleteall"))
                return complete(args[2], java.util.Arrays.stream(Bukkit.getOfflinePlayers()).map(org.bukkit.OfflinePlayer::getName).filter(java.util.Objects::nonNull).toList());
            if (args.length == 4) {
                var owner = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                        .filter(candidate -> candidate.getName() != null && candidate.getName().equalsIgnoreCase(args[2]))
                        .findFirst().orElse(null);
                if (owner == null) return List.of();
                var section = getConfig().getConfigurationSection("player-dialogues." + owner.getUniqueId());
                return complete(args[3], section == null ? List.of() : adminDialogueNames("player-dialogues." + owner.getUniqueId()));
            }
        }
        return List.of();
    }

    private List<String> complete(String input, List<String> values) {
        String prefix = input.toLowerCase();
        return values.stream().filter(value -> value.toLowerCase().startsWith(prefix)).sorted().toList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("RPG Maker 도움말", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/rpgmaker editor [이름]", NamedTextColor.YELLOW)
                .append(Component.text(" - 편집기 또는 저장 목록 열기", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker editor help", NamedTextColor.YELLOW)
                .append(Component.text(" - 에디터 상세 사용법", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker editor variables", NamedTextColor.YELLOW)
                .append(Component.text(" - 변수 형식과 기본 변수 목록", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker settings", NamedTextColor.YELLOW)
                .append(Component.text(" - 내 대화창 전체 크기 설정", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker items", NamedTextColor.YELLOW)
                .append(Component.text(" - 특수 아이템 편집기", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker examples restore", NamedTextColor.YELLOW)
                .append(Component.text(" - 삭제한 기본 연애·상점 예제 복구", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker play <이름>", NamedTextColor.YELLOW)
                .append(Component.text(" - 저장된 대화 재생", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker load <이름>", NamedTextColor.YELLOW)
                .append(Component.text(" - 저장된 대화 편집", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker list | delete <이름>", NamedTextColor.YELLOW)
                .append(Component.text(" - 목록 확인 또는 삭제", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker share <이름>", NamedTextColor.YELLOW)
                .append(Component.text(" - 전체 채팅에 읽기 전용으로 공유", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/rpgmaker publish <이름> | public", NamedTextColor.YELLOW)
                .append(Component.text(" - 공용 대화문 저장 또는 목록", NamedTextColor.GRAY)));
        if (sender.hasPermission("rpgmaker.admin")) {
            sender.sendMessage(Component.text("/rpgmaker play <공용명>", NamedTextColor.YELLOW)
                    .append(Component.text(" - 공용 대화문을 자신에게 표시", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker npc bind <NPC ID> <공용명> | unbind <NPC ID>", NamedTextColor.YELLOW)
                    .append(Component.text(" - Citizens NPC 우클릭 공용 대화 연결", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("NPC 생성 후 /npc id로 ID 확인 → /rpgmaker npc bind ...", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("OP 전용 명령어", NamedTextColor.RED));
            sender.sendMessage(Component.text("/rpgmaker edit", NamedTextColor.YELLOW).append(Component.text(" - 화면 배치 편집", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker edit2", NamedTextColor.YELLOW).append(Component.text(" - 인물 없는 대화창 배치 편집", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker edit3", NamedTextColor.YELLOW).append(Component.text(" - 화자 이름만 있는 배치 편집", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker edit4", NamedTextColor.YELLOW).append(Component.text(" - 캐릭터만 있는 배치 편집", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker save", NamedTextColor.YELLOW).append(Component.text(" - 화면 배치 저장", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker show <보여줄 플레이어> <저장 주인> <대화명>", NamedTextColor.YELLOW).append(Component.text(" - 다른 유저 저장본을 지정 대상에게 표시", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker close [플레이어]", NamedTextColor.YELLOW).append(Component.text(" - 진행 중인 대화 닫기", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker admin list <플레이어>", NamedTextColor.YELLOW).append(Component.text(" - 저장본 목록 보기", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker admin play <플레이어> <대화명>", NamedTextColor.YELLOW).append(Component.text(" - 다른 유저 저장본 재생", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker admin edit <플레이어> <대화명>", NamedTextColor.YELLOW).append(Component.text(" - 다른 유저 저장본 편집", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker admin delete <플레이어> <대화명>", NamedTextColor.YELLOW).append(Component.text(" - 다른 유저 저장본 삭제", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/rpgmaker admin deleteall CONFIRM", NamedTextColor.YELLOW).append(Component.text(" - 모든 일반 저장본 삭제", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("OP는 기타 설정에서 서버 명령어 효과도 편집할 수 있습니다.", NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("Tab 키로 하위 명령어와 저장 이름을 자동 완성할 수 있습니다.", NamedTextColor.AQUA));
    }

    private void showNamed(Player player, String name) {
        String path = dialoguePath(player, name);
        if (!getConfig().contains(path)) {
            player.sendMessage(Component.text("저장된 대화 '" + name + "'를 찾을 수 없습니다.", NamedTextColor.RED));
            return;
        }
        showPath(player, path);
    }

    void showNpcDialogue(Player player, String name) {
        String path = "public-dialogues." + sanitizeName(name);
        if (getConfig().contains(path)) showPath(player, path);
        else player.sendMessage(Component.text("공용 대화문 '" + name + "'을 찾을 수 없습니다.", NamedTextColor.RED));
    }

    boolean hasPublicDialogue(String name) {
        return getConfig().contains("public-dialogues." + sanitizeName(name));
    }

    private void showPath(Player player, String path) {
        List<String> messages = getConfig().getStringList(path + ".message-pages");
        String message = messages.isEmpty() ? getConfig().getString(path + ".message", "대화 내용") : String.join("\f", messages);
        if (message.startsWith("[B@")) message = "대화 내용을 다시 입력해 주세요.";
        java.util.ArrayList<List<Choice>> pageChoices = new java.util.ArrayList<>();
        int pageCount = splitPages(message).size();
        for (int page = 0; page < pageCount; page++) {
            String choicePath = path + ".page-choices." + page;
            if (!getConfig().contains(choicePath) && page == pageCount - 1) choicePath = path;
            pageChoices.add(loadChoices(choicePath, player));
        }
        boolean legacyPortrait = getConfig().getBoolean(path + ".show-portrait", true);
        String defaultSpeaker = getConfig().getString(path + ".speaker", "수호자");
        show(player, defaultSpeaker, message, List.of(),
                getConfig().getString(path + ".camera-direction", "NORTH"), getConfig().getDouble("distance", 1.8), true);
        Dialogue dialogue = active.get(player.getUniqueId());
        dialogue.pageChoices = pageChoices;
        String defaultPortrait = getConfig().getString(path + ".portrait", "SENTINEL");
        String defaultExpression = getConfig().getString(path + ".expression", "HAPPY");
        dialogue.pagePortraits = new java.util.ArrayList<>();
        dialogue.pageExpressions = new java.util.ArrayList<>();
        dialogue.pagePortraitVisible = new java.util.ArrayList<>();
        dialogue.pageSpeakerVisible = new java.util.ArrayList<>();
        String portrait = defaultPortrait;
        String expression = defaultExpression;
        for (int page = 0; page < pageCount; page++) {
            portrait = getConfig().getString(path + ".page-portraits." + page, portrait);
            expression = getConfig().getString(path + ".page-expressions." + page, expression);
            dialogue.pagePortraits.add(portrait);
            dialogue.pageExpressions.add(expression);
            boolean portraitVisible = getConfig().getBoolean(path + ".page-show-portraits." + page, legacyPortrait);
            dialogue.pagePortraitVisible.add(portraitVisible);
            dialogue.pageSpeakerVisible.add(getConfig().getBoolean(path + ".page-show-speakers." + page, portraitVisible));
        }
        dialogue.pageEffects = new java.util.ArrayList<>();
        for (int page = 0; page < pageCount; page++)
            dialogue.pageEffects.add(loadEffect(path + ".page-effects." + page));
        dialogue.pageConditions = new java.util.ArrayList<>();
        for (int page = 0; page < pageCount; page++) dialogue.pageConditions.add(loadCondition(path + ".page-conditions." + page));
        dialogue.pageRoutes = new java.util.ArrayList<>();
        for (int page = 0; page < pageCount; page++) dialogue.pageRoutes.add(loadPageRoute(path, page, defaultSpeaker));
        indexReturnTargets(dialogue);
        restartPage(dialogue, false);
    }

    private List<Choice> loadChoices(String path, Player player) {
        java.util.ArrayList<Choice> choices = new java.util.ArrayList<>();
        for (int i = 1; i <= Math.min(8, getConfig().getInt(path + ".choice-count", 0)); i++) {
            String label = getConfig().getString(path + ".choice-" + i, "");
            List<String> responsePages = getConfig().getStringList(path + ".response-pages-" + i);
            String response = responsePages.isEmpty() ? getConfig().getString(path + ".response-" + i, "") : String.join("\f", responsePages);
            int responsePageCount = splitPages(response).size();
            java.util.ArrayList<Effect> responseEffects = new java.util.ArrayList<>();
            java.util.ArrayList<String> responsePortraits = new java.util.ArrayList<>();
            java.util.ArrayList<String> responseExpressions = new java.util.ArrayList<>();
            java.util.ArrayList<Boolean> responsePortraitVisible = new java.util.ArrayList<>();
            java.util.ArrayList<Boolean> responseSpeakerVisible = new java.util.ArrayList<>();
            java.util.ArrayList<List<Choice>> responseChoices = new java.util.ArrayList<>();
            for (int page = 0; page < responsePageCount; page++) {
                String effectPath = path + ".response-effects-" + i + "." + page;
                if (page == 0 && !getConfig().contains(effectPath)) effectPath = path + ".effect-" + i;
                responseEffects.add(loadEffect(effectPath));
                responsePortraits.add(getConfig().getString(path + ".response-portrait-" + i + "." + page, ""));
                responseExpressions.add(getConfig().getString(path + ".response-expression-" + i + "." + page, ""));
                boolean portraitVisible = getConfig().getBoolean(path + ".response-show-portraits-" + i + "." + page, true);
                responsePortraitVisible.add(portraitVisible);
                responseSpeakerVisible.add(getConfig().getBoolean(path + ".response-show-speakers-" + i + "." + page, portraitVisible));
                responseChoices.add(loadChoices(path + ".response-page-choices-" + i + "." + page, player));
            }
            Condition condition = loadCondition(path + ".condition-" + i);
            if (!label.isBlank()) choices.add(new Choice(label, response, responseEffects, responsePortraits,
                    responseExpressions, responsePortraitVisible, responseSpeakerVisible, responseChoices,
                    getConfig().getBoolean(path + ".end-" + i, false), condition,
                    getConfig().getInt(path + ".target-page-" + i, 0),
                    getConfig().getString(path + ".speaker-" + i, "")));
        }
        return choices;
    }

    private Effect loadEffect(String path) {
        String items = getConfig().getString(path + ".items", "");
        String legacyItem = getConfig().getString(path + ".item", "");
        if (items.isBlank() && !legacyItem.isBlank()) items = legacyItem + ":" + getConfig().getInt(path + ".amount", 1);
        String takeItems = getConfig().getString(path + ".take-items", "");
        String legacyTake = getConfig().getString(path + ".take-item", "");
        if (takeItems.isBlank() && !legacyTake.isBlank()) takeItems = legacyTake + ":" + getConfig().getInt(path + ".take-amount", 1);
        String variablesSet = getConfig().getString(path + ".variables-set", "");
        String variablesDelete = getConfig().getString(path + ".variables-delete", "");
        String legacyVariable = getConfig().getString(path + ".variable", "");
        if (!legacyVariable.isBlank() && variablesSet.isBlank() && variablesDelete.isBlank()) {
            if (getConfig().getString(path + ".variable-action", "SET").equals("DELETE")) variablesDelete = legacyVariable;
            else variablesSet = legacyVariable + "=" + getConfig().getString(path + ".value", "");
        }
        String sounds = getConfig().getString(path + ".sounds", "");
        String legacySound = getConfig().getString(path + ".sound", "");
        if (sounds.isBlank() && !legacySound.isBlank()) sounds = legacySound + ":"
                + getConfig().getDouble(path + ".sound-pitch", 1.0) + ":"
                + getConfig().getDouble(path + ".sound-volume", 1.0) + ":1";
        return new Effect(items, takeItems, getConfig().getString(path + ".item-name", ""),
                getConfig().getString(path + ".item-color", "#FFFFFF"), variablesSet, variablesDelete,
                getConfig().getString(path + ".chat-input-variable", ""), sounds,
                getConfig().getString(path + ".message", ""), getConfig().getString(path + ".message-color", "#FFFFFF"),
                getConfig().getString(path + ".return-mode", "NONE"),
                getConfig().getString(path + ".return-target", ""),
                getConfig().getString(path + ".command", ""), getConfig().getString(path + ".command-target", "PLAYER"));
    }

    private Condition loadCondition(String path) {
        String itemSpec = getConfig().getString(path + ".item-spec", "");
        if (itemSpec.isBlank()) {
            String legacyItem = getConfig().getString(path + ".item", "");
            if (!legacyItem.isBlank()) {
                itemSpec = legacyItem + ":" + getConfig().getInt(path + ".amount", 1);
                String legacyName = getConfig().getString(path + ".item-name", "");
                if (!legacyName.isBlank()) itemSpec += ":" + legacyName;
            }
        }
        return new Condition(getConfig().getString(path + ".type", "NONE"),
                getConfig().getString(path + ".variable", ""), getConfig().getString(path + ".value", ""),
                getConfig().getString(path + ".operator", "EQ"),
                getConfig().getString(path + ".extra-variables", ""), getConfig().getString(path + ".variable-logic", "AND"),
                itemSpec, getConfig().getString(path + ".replacement", ""));
    }

    private PageRoute loadPageRoute(String root, int page, String defaultSpeaker) {
        String path = root + ".page-flow." + page;
        return new PageRoute(getConfig().getString(root + ".page-speakers." + page, defaultSpeaker),
                getConfig().getInt(path + ".next-page", 0), getConfig().getBoolean(path + ".terminal", false),
                getConfig().getInt(path + ".jump-target", 0), getConfig().getString(path + ".jump-timing", "AFTER"),
                loadCondition(path + ".condition"));
    }

    private void openContentEditor(Player player) {
        String path = editorPath(player);
        List<String> messages = editorMessages(player);
        int index = Math.min(editorPage.getOrDefault(player.getUniqueId(), 0), messages.size() - 1);
        boolean showPortrait = getConfig().getBoolean(path + ".page-show-portraits." + index,
                getConfig().getBoolean(path + ".show-portrait", true));
        boolean showSpeaker = getConfig().getBoolean(path + ".page-show-speakers." + index, showPortrait);
        java.util.ArrayList<DialogInput> inputs = new java.util.ArrayList<>();
        inputs.add(DialogInput.text("dialogue_name", Component.text("대화문 제목 (띄어쓰기 가능)")).width(400)
                .initial(getConfig().getString(path + ".title", editorName.getOrDefault(player.getUniqueId(), "default"))).maxLength(60).build());
        inputs.add(DialogInput.text("speaker", Component.text("화자 이름 (표시 최대 10자 · 서식 코드 제외)")).width(400)
                .initial(TextWidthRules.limitVisible(getConfig().getString(path + ".page-speakers." + index,
                        getConfig().getString(path + ".speaker", "수호자")), 10)).maxLength(128).build());
        String portrait = effectivePageAppearance(path, index, true);
        String character = characterFromPortrait(portrait);
        String gender = genderFromPortrait(portrait);
        String expression = effectivePageAppearance(path, index, false);
        String[] savedLines = messages.get(index).split("\\n", -1);
        int maximumLines = MAXIMUM_LINES;
        for (int i = 1; i <= maximumLines; i++) {
            inputs.add(DialogInput.text("line_" + i, Component.text("대사 " + (index + 1) + " - " + i + "줄 (표시 30자 · #FF0000:단어)")).width(500)
                    .initial(limitLine(i <= savedLines.length ? savedLines[i - 1] : "")).maxLength(MAXIMUM_CHARACTERS_PER_LINE * 4).build());
        }
        ActionButton save = ActionButton.builder(Component.text("저장하기", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "save_editor"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton preview = ActionButton.builder(Component.text("미리보기", NamedTextColor.AQUA)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "preview_editor"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton previous = ActionButton.builder(Component.text("← 이전 대사", NamedTextColor.AQUA)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "previous_dialogue"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton next = ActionButton.builder(Component.text("다음 대사 →", NamedTextColor.AQUA)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "next_dialogue"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton deletePage = ActionButton.builder(Component.text("현재 페이지 삭제", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "delete_current_page"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton addChoice = ActionButton.builder(Component.text("선택지 추가하기", NamedTextColor.YELLOW)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "add_choice"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton viewChoices = ActionButton.builder(Component.text("저장된 선택지 보기/수정", NamedTextColor.GOLD)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "view_choices"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton discard = ActionButton.builder(Component.text("저장하지 않기", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "discard_editor"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton settings = ActionButton.builder(Component.text("기타 설정", NamedTextColor.YELLOW)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "page_settings"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton delete = ActionButton.builder(Component.text("대화문 삭제", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "delete_current_dialogue"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 대화 목록", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "discard_editor"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton characterButton = appearanceButton("이미지: " + characterLabel(character), "page_appearance_character", NamedTextColor.AQUA);
        ActionButton genderButton = appearanceButton("성별: " + genderLabel(character, gender), "page_appearance_gender", NamedTextColor.LIGHT_PURPLE);
        ActionButton expressionButton = appearanceButton("표정: " + expressionLabel(expression), "page_appearance_expression", NamedTextColor.GOLD);
        ActionButton portraitToggle = ActionButton.builder(Component.text("캐릭터: " + (showPortrait ? "표시" : "숨김"),
                        showPortrait ? NamedTextColor.GREEN : NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "toggle_portrait"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton speakerToggle = ActionButton.builder(Component.text("화자 이름: " + (showSpeaker ? "표시" : "숨김"),
                        showSpeaker ? NamedTextColor.GREEN : NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "toggle_speaker"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>();
        if (showPortrait) buttons.addAll(List.of(characterButton, genderButton, expressionButton));
        buttons.addAll(List.of(previous, next, portraitToggle, speakerToggle, deletePage, addChoice, viewChoices,
                discard, save, settings, preview, delete, back));
        Dialog dialog = Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text((index + 1) + " / " + messages.size() + " 페이지 (최대 30)", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(inputs).build()).type(DialogType.multiAction(buttons).columns(3).build()));
        player.showDialog(dialog);
    }

    private void openChoiceList(Player player) {
        String path = choicePath(player);
        int count = Math.min(8, getConfig().getInt(path + ".choice-count", 0));
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String label = getConfig().getString(path + ".choice-" + i, "선택지 " + i);
            buttons.add(ActionButton.builder(Component.text(i + ". " + label, NamedTextColor.YELLOW)).width(200)
                    .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_edit_" + i), BinaryTagHolder.binaryTagHolder("{}"))).build());
        }
        if (count < 8) buttons.add(ActionButton.builder(Component.text("새 선택지 추가", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_new"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        buttons.add(ActionButton.builder(Component.text("← 대사 편집으로", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_content"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        int page = editorPage.getOrDefault(player.getUniqueId(), 0) + 1;
        String title = count == 0 ? page + "페이지: 선택지가 없습니다" : page + "페이지 선택지";
        Dialog dialog = Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(1).build()));
        player.showDialog(dialog);
    }

    private void openQuickActions(Player player) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + player.getName() + " rpgmaker:editor");
    }

    private void openPlayerSettings(Player player) {
        int percent = (int) Math.round(playerUiScale(player) * 100.0);
        List<ActionButton> buttons = List.of(
                ActionButton.builder(Component.text("10% 작게", NamedTextColor.AQUA)).width(150)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "ui_scale_down"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                ActionButton.builder(Component.text("10% 크게", NamedTextColor.GREEN)).width(150)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "ui_scale_up"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                ActionButton.builder(Component.text("100%로 초기화", NamedTextColor.YELLOW)).width(150)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "ui_scale_reset"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                ActionButton.builder(Component.text("← 전체 메뉴", NamedTextColor.GRAY)).width(150)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "ui_scale_back"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        Dialog dialog = Dialog.create(factory -> factory.empty().base(DialogBase.builder(
                        Component.text("내 대화창 크기 · " + percent + "% · 범위 60~140%", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    private double playerUiScale(Player player) {
        Double stored = player.getPersistentDataContainer().get(uiScaleKey, PersistentDataType.DOUBLE);
        return clampUiScale(stored == null ? 1.0 : stored);
    }

    private double effectiveUiScale(Dialogue dialogue) {
        return dialogue.editing ? 1.0 : playerUiScale(dialogue.player);
    }

    private double clampUiScale(double value) {
        return Double.isFinite(value) ? Math.max(MINIMUM_UI_SCALE, Math.min(MAXIMUM_UI_SCALE, value)) : 1.0;
    }

    private void setPlayerUiScale(Player player, double value) {
        double scale = Math.round(clampUiScale(value) * 10.0) / 10.0;
        player.getPersistentDataContainer().set(uiScaleKey, PersistentDataType.DOUBLE, scale);
        player.saveData();
        Dialogue dialogue = active.get(player.getUniqueId());
        if (dialogue != null) {
            applyScales(dialogue);
            position(dialogue);
        }
    }

    private void sendEditorHelp(CommandSender sender) {
        sender.sendMessage(Component.text("RPGMaker 에디터 도움말", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("제목은 공백 포함 60자, 화자 이름은 10자까지 입력합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("대사 1~4줄은 줄마다 공백 포함 30자까지 입력합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("색상·서식: #FF0000:bold,italic,strikethrough:단어  |  변수 출력: {{변수이름}}", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("대사와 화자 이름 모두 색상·굵기·기울임·취소선을 지원합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("채팅 입력 저장에는 변수 이름만 입력합니다. 예: nickname", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("해당 대사를 넘긴 뒤 플레이어의 다음 채팅 1회가 저장되며, 채팅에는 표시되지 않습니다. 이후 {{nickname}}으로 출력합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("변수는 문자열·true/false를 저장하며 +=, -=, *=, /= 및 random(최소..최대) 난수를 지원합니다.", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("기본 변수: {{player_name}}, {{player_world}}, {{player_x}}, {{player_y}}, {{player_z}}", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("손 아이템 변수: {{held_item_name}}, {{held_item_type}}, {{held_item_amount}}", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("이미지·성별·표정은 페이지마다 설정합니다. 다음 대사는 현재 내용을 저장하고 새 페이지를 엽니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("선택지는 목표 대사 번호와 변경 화자를 지정합니다. 목표 0만 기존 후속 대사를 사용합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("대화 흐름 설정에서 다음 번호, 조건 이동 전/후, 조건부 종결을 페이지마다 지정합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("기타 설정: 아이템 지급/소모, 변수, 사운드, 채팅, 표시 조건. OP는 서버 명령어도 설정합니다.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("저장하기는 계속 편집하고, 미리보기는 현재 대화를 실행합니다. 저장하지 않기는 목록으로 돌아갑니다.", NamedTextColor.GRAY));
    }

    private void openEditorList(Player player) {
        ensureExamples(player);
        List<String> names = dialogueNames(player);
        editorLists.put(player.getUniqueId(), names);
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String title = getConfig().getString(dialoguePath(player, names.get(i)) + ".title", names.get(i));
            buttons.add(ActionButton.builder(Component.text("편집: " + title, NamedTextColor.AQUA)).width(200)
                    .action(DialogAction.customClick(Key.key("dialoguedisplay", "editor_load_" + i), BinaryTagHolder.binaryTagHolder("{}"))).build());
        }
        buttons.add(ActionButton.builder(Component.text("← 전체 메뉴", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_quick_actions"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        Dialog dialog = Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("저장된 대화 불러오기", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build()));
        player.showDialog(dialog);
    }

    private void openChoiceEditor(Player player) {
        String path = choicePath(player);
        int index = editorChoice.getOrDefault(player.getUniqueId(), 1);
        List<String> pages = choiceResponsePages(player, index);
        int page = Math.min(editorChoicePage.getOrDefault(player.getUniqueId(), 0), pages.size() - 1);
        editorChoicePage.put(player.getUniqueId(), page);
        String[] savedLines = pages.get(page).split("\\n", -1);
        int maximumLines = MAXIMUM_LINES;
        java.util.ArrayList<DialogInput> inputs = new java.util.ArrayList<>();
        inputs.add(DialogInput.text("choice", Component.text("선택지 " + index + " 이름 (최대 10자)")).width(500)
                .initial(limitText(getConfig().getString(path + ".choice-" + index, ""), 10)).maxLength(10).build());
        inputs.add(DialogInput.singleOption("choice_target_page", Component.text("선택 시 이동할 대사 번호"),
                pageNumberOptions(player, getConfig().getInt(path + ".target-page-" + index, 0),
                        "0 · 레거시 후속 대사 사용")).width(500).build());
        inputs.add(DialogInput.text("choice_speaker", Component.text("이 선택지 이후 화자 이름 · 표시 10자 · 서식 코드 제외")).width(500)
                .initial(TextWidthRules.limitVisible(getConfig().getString(path + ".speaker-" + index, ""), 10)).maxLength(128).build());
        String responsePortrait = getConfig().getString(path + ".response-portrait-" + index + "." + page, "SENTINEL");
        String responseExpression = getConfig().getString(path + ".response-expression-" + index + "." + page, "HAPPY");
        boolean responsePortraitVisible = getConfig().getBoolean(path + ".response-show-portraits-" + index + "." + page, true);
        boolean responseSpeakerVisible = getConfig().getBoolean(path + ".response-show-speakers-" + index + "." + page, responsePortraitVisible);
        for (int i = 1; i <= maximumLines; i++) {
            inputs.add(DialogInput.text("line_" + i, Component.text("레거시 후속 대사 " + index + "-" + (page + 1) + " · " + i + "줄 (목표 0일 때만)")).width(500)
                    .initial(limitLine(i <= savedLines.length ? savedLines[i - 1] : "")).maxLength(MAXIMUM_CHARACTERS_PER_LINE * 4).build());
        }
        inputs.add(DialogInput.singleOption("choice_flow", Component.text("이 선택지를 골랐을 때"), List.of(
                SingleOptionDialogInput.OptionEntry.create("CONTINUE", Component.text("후속 대사 후 다음 페이지 계속"), !getConfig().getBoolean(path + ".end-" + index, false)),
                SingleOptionDialogInput.OptionEntry.create("END", Component.text("후속 대사 후 쉬프트로 종료"), getConfig().getBoolean(path + ".end-" + index, false))
        )).width(500).build());
        ActionButton save = ActionButton.builder(Component.text("선택지 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "save_choice"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton add = ActionButton.builder(Component.text("선택지 하나 더", NamedTextColor.YELLOW)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "add_next_choice"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton previous = ActionButton.builder(Component.text("← 이전 후속 대사", NamedTextColor.AQUA)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_page_previous"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton next = ActionButton.builder(Component.text("다음 후속 대사 →", NamedTextColor.AQUA)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_page_next"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton addPage = ActionButton.builder(Component.text("새 후속 대사 추가", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_page_add"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton deletePage = ActionButton.builder(Component.text("현재 페이지 삭제", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "delete_choice_page"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton delete = ActionButton.builder(Component.text("선택지 삭제", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "delete_choice"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton discard = ActionButton.builder(Component.text("저장하지 않기", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "discard_choice"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 선택지 목록으로", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_choice_list"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton settings = ActionButton.builder(Component.text("기타 설정", NamedTextColor.YELLOW)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_settings"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton nested = ActionButton.builder(Component.text("후속 선택지 추가/수정", NamedTextColor.GOLD)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_nested_choices"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        String responseCharacter = characterFromPortrait(responsePortrait);
        ActionButton characterButton = appearanceButton("이미지: " + characterLabel(responseCharacter), "choice_appearance_character", NamedTextColor.AQUA);
        ActionButton genderButton = appearanceButton("성별: " + genderLabel(responseCharacter, genderFromPortrait(responsePortrait)), "choice_appearance_gender", NamedTextColor.LIGHT_PURPLE);
        ActionButton expressionButton = appearanceButton("표정: " + expressionLabel(responseExpression), "choice_appearance_expression", NamedTextColor.GOLD);
        ActionButton portraitToggle = ActionButton.builder(Component.text("캐릭터: " + (responsePortraitVisible ? "표시" : "숨김"),
                        responsePortraitVisible ? NamedTextColor.GREEN : NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_toggle_portrait"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton speakerToggle = ActionButton.builder(Component.text("화자 이름: " + (responseSpeakerVisible ? "표시" : "숨김"),
                        responseSpeakerVisible ? NamedTextColor.GREEN : NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "choice_toggle_speaker"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>(List.of(
                characterButton, genderButton, expressionButton, portraitToggle, speakerToggle,
                previous, next, deletePage, addPage, save, add, delete, settings, back));
        if (!choiceRootOverride.containsKey(player.getUniqueId())) buttons.add(nested);
        buttons.add(discard);
        Dialog dialog = Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("선택지 " + index + "-" + (page + 1) + " / " + pages.size(), NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(inputs).build()).type(DialogType.multiAction(buttons).columns(3).build()));
        player.showDialog(dialog);
    }

    private void openItemEditorList(Player player) {
        List<String> names = customItemNames(player.getUniqueId());
        long custom = names.stream().filter(name -> getConfig().contains(
                customItemRoot(player.getUniqueId()) + "." + name + ".item-bytes")).count();
        long special = names.size() - custom;
        List<ActionButton> buttons = List.of(
                ActionButton.builder(Component.text("커스텀 아이템 · " + custom + "개", NamedTextColor.GOLD)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_custom_list"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                ActionButton.builder(Component.text("특수 아이템 · " + special + "개", NamedTextColor.AQUA)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_special_list"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                itemSectionButton("── 새 아이템 만들기 ──"), itemSectionButton("종류를 선택하세요"),
                ActionButton.builder(Component.text("손 아이템 저장하기", NamedTextColor.GOLD)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_capture_new"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                ActionButton.builder(Component.text("새 특수 아이템 만들기", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_new"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        java.util.ArrayList<ActionButton> all = new java.util.ArrayList<>(buttons);
        all.add(ActionButton.builder(Component.text("← 전체 메뉴", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_quick_actions"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("아이템 편집기", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(all).columns(2).build())));
    }

    private void openItemCategoryList(Player player, boolean captured) {
        List<String> names = customItemNames(player.getUniqueId()).stream().filter(name -> getConfig().contains(
                customItemRoot(player.getUniqueId()) + "." + name + ".item-bytes") == captured).toList();
        itemEditorLists.put(player.getUniqueId(), names);
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            String path = customItemRoot(player.getUniqueId()) + "." + names.get(index);
            String title = getConfig().getString(path + ".title", names.get(index));
            buttons.add(ActionButton.builder(Component.text((captured ? "확인: " : "편집: ") + title,
                            captured ? NamedTextColor.GOLD : NamedTextColor.AQUA)).width(200)
                    .action(DialogAction.customClick(Key.key("dialoguedisplay", (captured ? "item_editor_view_" : "item_editor_edit_") + index), BinaryTagHolder.binaryTagHolder("{}"))).build());
        }
        buttons.add(ActionButton.builder(Component.text("← 아이템 편집기", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_open"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(
                        captured ? "커스텀 아이템" : "특수 아이템", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private ActionButton itemSectionButton(String label) {
        return ActionButton.builder(Component.text(label, NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_section"), BinaryTagHolder.binaryTagHolder("{}"))).build();
    }

    private void openDeleteConfirmation(Player player, String target, String confirmAction, String cancelAction) {
        ActionButton confirm = ActionButton.builder(Component.text("정말 삭제하기", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", confirmAction), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton cancel = ActionButton.builder(Component.text("← 취소", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", cancelAction), BinaryTagHolder.binaryTagHolder("{}"))).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(
                        target + "을(를) 정말 삭제하시겠습니까?", NamedTextColor.RED))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(List.of(confirm, cancel)).columns(2).build())));
    }

    private void openListedItemEditor(Player player, String action, boolean delete) {
        try {
            int index = Integer.parseInt(action.substring(action.lastIndexOf('_') + 1));
            List<String> names = itemEditorLists.getOrDefault(player.getUniqueId(), List.of());
            if (index < 0 || index >= names.size()) return;
            String name = names.get(index);
            if (delete) {
                getConfig().set(customItemRoot(player.getUniqueId()) + "." + name, null);
                saveConfig();
                player.sendMessage(Component.text("특수 아이템 '" + name + "'을 삭제했습니다.", NamedTextColor.RED));
                Bukkit.getScheduler().runTask(this, () -> openItemEditorList(player));
            } else {
                itemEditorName.put(player.getUniqueId(), name);
                Bukkit.getScheduler().runTask(this, () -> openItemEditor(player));
            }
        } catch (NumberFormatException ignored) { }
    }

    private void openListedCapturedItem(Player player, String action) {
        try {
            int index = Integer.parseInt(action.substring(action.lastIndexOf('_') + 1));
            List<String> names = itemEditorLists.getOrDefault(player.getUniqueId(), List.of());
            if (index < 0 || index >= names.size()) return;
            itemEditorName.put(player.getUniqueId(), names.get(index));
            Bukkit.getScheduler().runTask(this, () -> openCapturedItemView(player));
        } catch (NumberFormatException ignored) { }
    }

    private void openCapturedItemView(Player player) {
        String key = itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
        String path = customItemRoot(player.getUniqueId()) + "." + key;
        List<ActionButton> buttons = List.of(
                itemSectionButton("저장명: " + getConfig().getString(path + ".title", key)),
                itemSectionButton("종류: " + getConfig().getString(path + ".material", "알 수 없음")),
                ActionButton.builder(Component.text("커스텀 아이템 삭제", NamedTextColor.RED)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_delete_current"), BinaryTagHolder.binaryTagHolder("{}"))).build(),
                ActionButton.builder(Component.text("← 커스텀 아이템 목록", NamedTextColor.GRAY)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_back_list"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("커스텀 아이템 확인", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private void openItemEditor(Player player) {
        String key = itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
        String path = customItemRoot(player.getUniqueId()) + "." + key;
        List<String> lore = getConfig().getStringList(path + ".lore-lines");
        List<String> colors = getConfig().getStringList(path + ".lore-colors");
        java.util.ArrayList<DialogInput> inputs = new java.util.ArrayList<>();
        inputs.add(DialogInput.text("custom_item_key", Component.text("저장 이름")).width(500)
                .initial(getConfig().getString(path + ".title", key)).maxLength(30).build());
        inputs.add(DialogInput.text("custom_item_material", Component.text("아이템 종류 · 예: minecraft:diamond_sword")).width(500)
                .initial(getConfig().getString(path + ".material", "minecraft:paper")).maxLength(100).build());
        inputs.add(DialogInput.text("custom_item_name", Component.text("표시 이름")).width(500)
                .initial(getConfig().getString(path + ".display-name", "특수 아이템")).maxLength(100).build());
        inputs.add(DialogInput.text("custom_item_name_color", Component.text("이름 색 · 예: #FFAA00")).width(500)
                .initial(getConfig().getString(path + ".name-color", "#FFFFFF")).maxLength(7).build());
        for (int line = 0; line < MAXIMUM_LINES; line++) {
            inputs.add(DialogInput.text("custom_item_lore_" + line, Component.text("Lore " + (line + 1) + "줄")).width(500)
                    .initial(line < lore.size() ? lore.get(line) : "").maxLength(200).build());
            inputs.add(DialogInput.text("custom_item_lore_color_" + line, Component.text("Lore " + (line + 1) + "줄 색")).width(500)
                    .initial(line < colors.size() ? colors.get(line) : "#AAAAAA").maxLength(7).build());
        }
        ActionButton save = ActionButton.builder(Component.text("아이템 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_save"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton capture = ActionButton.builder(Component.text("손 아이템 전체 저장", NamedTextColor.GOLD)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_capture"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton delete = ActionButton.builder(Component.text("특수 아이템 삭제", NamedTextColor.RED)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_delete_current"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 특수 아이템 목록", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_back_list"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("특수 아이템 편집", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(inputs).build()).type(DialogType.multiAction(List.of(capture, save, delete, back)).columns(2).build())));
    }

    private boolean saveItemEditor(Player player, DialogResponseView response) {
        String title = response.getText("custom_item_key");
        Material material = Material.matchMaterial(response.getText("custom_item_material"));
        if (title == null || title.isBlank() || material == null) {
            player.sendMessage(Component.text("저장 이름과 올바른 아이템 종류가 필요합니다.", NamedTextColor.RED));
            return false;
        }
        String oldKey = itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
        String key = sanitizeName(title);
        String root = customItemRoot(player.getUniqueId());
        String path = root + "." + key;
        if (!oldKey.equals(key)) getConfig().set(root + "." + oldKey, null);
        getConfig().set(path + ".title", title.strip());
        getConfig().set(path + ".material", material.getKey().asString());
        getConfig().set(path + ".display-name", response.getText("custom_item_name"));
        getConfig().set(path + ".name-color", response.getText("custom_item_name_color"));
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        java.util.ArrayList<String> colors = new java.util.ArrayList<>();
        for (int line = 0; line < MAXIMUM_LINES; line++) {
            lore.add(response.getText("custom_item_lore_" + line));
            colors.add(response.getText("custom_item_lore_color_" + line));
        }
        while (!lore.isEmpty() && lore.get(lore.size() - 1).isBlank()) {
            lore.remove(lore.size() - 1);
            colors.remove(colors.size() - 1);
        }
        getConfig().set(path + ".lore-lines", lore);
        getConfig().set(path + ".lore-colors", colors);
        getConfig().set(path + ".item-stack", null);
        getConfig().set(path + ".item-bytes", null);
        itemEditorName.put(player.getUniqueId(), key);
        saveConfig();
        player.sendMessage(Component.text("특수 아이템 '" + title.strip() + "'을 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private void openHeldItemNameDialog(Player player) {
        String initial = itemEditorName.getOrDefault(player.getUniqueId(), "new_item");
        DialogInput name = DialogInput.text("captured_item_key", Component.text("불러올 이름")).width(500)
                .initial(initial).maxLength(30).build();
        ActionButton save = ActionButton.builder(Component.text("손 아이템 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_capture_save"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton cancel = ActionButton.builder(Component.text("← 취소", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "item_editor_capture_cancel"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("손 아이템 전체 저장", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(List.of(name)).build()).type(DialogType.multiAction(List.of(save, cancel)).columns(2).build())));
    }

    private boolean captureHeldItem(Player player, String title) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            player.sendMessage(Component.text("손에 아이템을 들고 다시 눌러 주세요.", NamedTextColor.RED));
            return false;
        }
        if (title == null || title.isBlank()) {
            player.sendMessage(Component.text("불러올 이름을 입력해 주세요.", NamedTextColor.RED));
            return false;
        }
        ItemStack stored = held.clone();
        stored.setAmount(1);
        String key = sanitizeName(title);
        String path = customItemRoot(player.getUniqueId()) + "." + key;
        getConfig().set(path, null);
        getConfig().set(path + ".title", title.strip());
        getConfig().set(path + ".material", stored.getType().getKey().asString());
        getConfig().set(path + ".item-bytes", Base64.getEncoder().encodeToString(stored.serializeAsBytes()));
        itemEditorName.put(player.getUniqueId(), key);
        saveConfig();
        player.sendMessage(Component.text("손에 든 아이템 전체를 '" + title.strip() + "'으로 저장했습니다.", NamedTextColor.GREEN));
        return true;
    }

    private ActionButton appearanceButton(String label, String action, NamedTextColor color) {
        return ActionButton.builder(Component.text(label, color)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", action), BinaryTagHolder.binaryTagHolder("{}"))).build();
    }

    private void openAppearancePicker(Player player, boolean choiceMode, String type) {
        appearanceChoiceMode.put(player.getUniqueId(), choiceMode);
        String portrait = appearanceValuePath(player, choiceMode, true);
        String expression = appearanceValuePath(player, choiceMode, false);
        String character = characterFromPortrait(portrait);
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>();
        String title;
        if (type.equals("character")) {
            title = "캐릭터 이미지 선택";
            List<String> values = List.of("SENTINEL", "WARRIOR", "KING", "MAGE", "ARCHER", "DEMON",
                    "KNIGHT", "MAGE_CLASS", "RANGER", "CLERIC", "ROGUE", "NOBLE",
                    "VILLAGER", "BLACKSMITH", "INNKEEPER", "SLIME", "GOBLIN", "ORC", "DRAGONKIN");
            for (String value : values) buttons.add(appearanceButton(characterLabel(value),
                    "pick_character_" + value.toLowerCase(java.util.Locale.ROOT),
                    value.equals(character) ? NamedTextColor.GOLD : NamedTextColor.AQUA));
        } else if (type.equals("gender")) {
            title = "성별 선택 · 선택 즉시 저장";
            String gender = genderFromPortrait(portrait);
            buttons.add(appearanceButton("남성", "pick_gender_male", gender.equals("MALE") ? NamedTextColor.GOLD : NamedTextColor.AQUA));
            buttons.add(appearanceButton("여성", "pick_gender_female", gender.equals("FEMALE") ? NamedTextColor.GOLD : NamedTextColor.LIGHT_PURPLE));
        } else {
            title = "표정 선택";
            for (String value : availableExpressions(portrait)) buttons.add(appearanceButton(expressionLabel(value),
                    "pick_expression_" + value.toLowerCase(java.util.Locale.ROOT),
                    value.equals(expression) ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
        }
        buttons.add(appearanceButton("← 편집창으로", "pick_back", NamedTextColor.GRAY));
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(3).build())));
    }

    private void handleAppearancePick(Player player, String pick) {
        boolean choiceMode = appearanceChoiceMode.getOrDefault(player.getUniqueId(), false);
        if (!pick.equals("back")) {
            String portraitPath = appearanceConfigPath(player, choiceMode, true);
            String expressionPath = appearanceConfigPath(player, choiceMode, false);
            String portrait = appearanceValuePath(player, choiceMode, true);
            String expression = appearanceValuePath(player, choiceMode, false);
            if (pick.startsWith("character_")) {
                String character = pick.substring("character_".length()).toUpperCase(java.util.Locale.ROOT);
                portrait = resolvePortrait(character, genderFromPortrait(portrait));
            } else if (pick.startsWith("gender_") && hasGender(characterFromPortrait(portrait))) {
                portrait = resolvePortrait(characterFromPortrait(portrait),
                        pick.substring("gender_".length()).toUpperCase(java.util.Locale.ROOT));
            } else if (pick.startsWith("expression_")) {
                expression = pick.substring("expression_".length()).toUpperCase(java.util.Locale.ROOT);
            }
            expression = normalizeExpression(portrait, expression);
            getConfig().set(portraitPath, portrait);
            getConfig().set(expressionPath, expression);
            saveConfig();
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (choiceMode) openChoiceEditor(player); else openContentEditor(player);
        });
    }

    private String appearanceConfigPath(Player player, boolean choiceMode, boolean portrait) {
        if (!choiceMode) return editorPath(player) + (portrait ? ".page-portraits." : ".page-expressions.")
                + editorPage.getOrDefault(player.getUniqueId(), 0);
        int choice = editorChoice.getOrDefault(player.getUniqueId(), 1);
        int page = editorChoicePage.getOrDefault(player.getUniqueId(), 0);
        return choicePath(player) + (portrait ? ".response-portrait-" : ".response-expression-") + choice + "." + page;
    }

    private String appearanceValuePath(Player player, boolean choiceMode, boolean portrait) {
        if (!choiceMode) return effectivePageAppearance(editorPath(player),
                editorPage.getOrDefault(player.getUniqueId(), 0), portrait);
        return getConfig().getString(appearanceConfigPath(player, true, portrait), portrait ? "SENTINEL" : "HAPPY");
    }

    private String effectivePageAppearance(String path, int page, boolean portrait) {
        String value = getConfig().getString(path + (portrait ? ".portrait" : ".expression"),
                portrait ? "SENTINEL" : "HAPPY");
        for (int i = 0; i <= page; i++)
            value = getConfig().getString(path + (portrait ? ".page-portraits." : ".page-expressions.") + i, value);
        return value;
    }

    private List<String> availableExpressions(String portrait) {
        if (List.of("SLIME", "GOBLIN", "ORC", "DRAGONKIN").contains(portrait)) return List.of("NEUTRAL");
        return supportsEmbarrassed(portrait)
                ? List.of("NEUTRAL", "HAPPY", "SAD", "ANGRY", "SURPRISED", "EMBARRASSED")
                : List.of("NEUTRAL", "HAPPY", "SAD", "ANGRY", "SURPRISED");
    }

    private String normalizeExpression(String portrait, String expression) {
        return availableExpressions(portrait).contains(expression) ? expression : "NEUTRAL";
    }

    private boolean supportsEmbarrassed(String portrait) {
        return portrait.startsWith("FEMALE_") || List.of("MAGE", "ARCHER").contains(portrait);
    }

    private boolean hasGender(String character) {
        return !List.of("SENTINEL", "DEMON", "SLIME", "GOBLIN", "ORC", "DRAGONKIN").contains(character);
    }

    private String characterLabel(String character) {
        return switch (character) {
            case "SENTINEL" -> "수호자"; case "WARRIOR" -> "전사"; case "KING" -> "왕";
            case "MAGE", "MAGE_CLASS" -> "마법사"; case "ARCHER" -> "궁수"; case "DEMON" -> "악마";
            case "KNIGHT" -> "기사"; case "RANGER" -> "레인저"; case "CLERIC" -> "성직자";
            case "ROGUE" -> "도적"; case "NOBLE" -> "귀족"; case "VILLAGER" -> "마을 주민";
            case "BLACKSMITH" -> "대장장이"; case "INNKEEPER" -> "여관주인"; case "SLIME" -> "슬라임";
            case "GOBLIN" -> "고블린"; case "ORC" -> "오크"; case "DRAGONKIN" -> "용인";
            default -> character;
        };
    }

    private String genderLabel(String character, String gender) {
        return hasGender(character) ? (gender.equals("FEMALE") ? "여성" : "남성") : "없음";
    }

    private String expressionLabel(String expression) {
        return switch (expression) {
            case "NEUTRAL" -> "무표정"; case "HAPPY" -> "기쁨"; case "SAD" -> "슬픔";
            case "ANGRY" -> "화남"; case "SURPRISED" -> "당황"; case "EMBARRASSED" -> "부끄러움";
            default -> expression;
        };
    }

    private void openSettingsHub(Player player, boolean choiceMode) {
        effectChoiceMode.put(player.getUniqueId(), choiceMode);
        java.util.ArrayList<ActionButton> buttons = new java.util.ArrayList<>(List.of(
                settingsButton("아이템 설정하기", "item", NamedTextColor.GOLD),
                settingsButton("변수 설정하기", "variable", NamedTextColor.AQUA),
                settingsButton("사운드 설정하기", "sound", NamedTextColor.GREEN),
                settingsButton("채팅 메시지 설정하기", "message", NamedTextColor.WHITE),
                settingsButton("이전 진행으로 돌아가기", "return", NamedTextColor.AQUA),
                settingsButton("표시 조건", "condition", NamedTextColor.YELLOW)));
        if (!choiceMode) buttons.add(0, settingsButton("대화 흐름 설정", "flow", NamedTextColor.GREEN));
        if (player.hasPermission("rpgmaker.admin"))
            buttons.add(settingsButton("명령어 설정하기 (OP)", "command", NamedTextColor.RED));
        buttons.add(settingsButton("← 편집 화면으로", "back", NamedTextColor.GRAY));
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(
                        choiceMode ? "선택지 후속 대사 설정" : "대사 페이지 설정", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private ActionButton settingsButton(String label, String action, NamedTextColor color) {
        return ActionButton.builder(Component.text(label, color)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "settings_" + action), BinaryTagHolder.binaryTagHolder("{}"))).build();
    }

    private String effectPath(Player player, boolean choiceMode) {
        return choiceMode ? choiceEffectPath(player)
                : editorPath(player) + ".page-effects." + editorPage.getOrDefault(player.getUniqueId(), 0);
    }

    private String effectReadPath(Player player, boolean choiceMode) {
        String path = effectPath(player, choiceMode);
        if (choiceMode && !getConfig().contains(path) && editorChoicePage.getOrDefault(player.getUniqueId(), 0) == 0)
            return choicePath(player) + ".effect-" + editorChoice.getOrDefault(player.getUniqueId(), 1);
        return path;
    }

    private void openEffectEditor(Player player, boolean choiceMode, String section) {
        effectChoiceMode.put(player.getUniqueId(), choiceMode);
        effectSection.put(player.getUniqueId(), section);
        if (section.equals("RETURN")) {
            openReturnEditor(player, choiceMode);
            return;
        }
        String path = effectReadPath(player, choiceMode);
        Effect effect = loadEffect(path);
        java.util.ArrayList<DialogInput> inputs = new java.util.ArrayList<>();
        String title;
        switch (section) {
            case "VARIABLE" -> {
                title = "변수 설정";
                inputs.add(DialogInput.text("effect_chat_input_variable", Component.text("플레이어의 채팅 입력값을 아래 변수에 저장합니다")).width(500)
                        .initial(effect.chatInputVariable).maxLength(100).build());
                inputs.add(DialogInput.text("effect_variables_set", Component.text("설정·연산 · 이름=값, 점수+=1, 값*=2, 값/=2, damage_roll=random(5..20)")).width(500)
                        .initial(effect.variablesSet).maxLength(2000).build());
                inputs.add(DialogInput.text("effect_variables_delete", Component.text("삭제 · 이름, 이름2")).width(500)
                        .initial(effect.variablesDelete).maxLength(1000).build());
            }
            case "SOUND" -> {
                title = "사운드 설정";
                inputs.add(DialogInput.text("effect_sounds", Component.text("형식: minecraft:소리ID:피치(0.5~2):음량(0~4):반복횟수(1~10) · 여러 개는 쉼표로 구분")).width(500)
                        .initial(effect.sounds).maxLength(3000).build());
            }
            case "MESSAGE" -> {
                title = "채팅 메시지 설정";
                inputs.add(DialogInput.text("effect_message", Component.text("플레이어에게 보낼 채팅 메시지")).width(500)
                        .initial(effect.message).maxLength(2000).build());
                inputs.add(DialogInput.text("effect_message_color", Component.text("메시지 색상 HEX · 예: #FFAA00")).width(500)
                        .initial(effect.messageColor).maxLength(7).build());
            }
            case "COMMAND" -> {
                title = "서버 명령어 설정 (OP)";
                String target = getConfig().getString(path + ".command-target", "PLAYER");
                inputs.add(DialogInput.text("effect_command", Component.text("명령어 · / 제외 · {target}, {player} 사용 가능")).width(500)
                        .initial(getConfig().getString(path + ".command", "")).maxLength(1000).build());
                inputs.add(DialogInput.singleOption("command_target", Component.text("{target} 대상"), List.of(
                        option("PLAYER", "대화 중인 플레이어", target), option("ALL", "모든 플레이어 (@a)", target),
                        option("NEAREST", "가장 가까운 플레이어 (@p)", target))).width(500).build());
            }
            default -> {
                title = "아이템 지급·소모 설정 · 여러 개 가능";
                inputs.add(DialogInput.text("effect_items", Component.text("지급 형식: minecraft:아이템ID:개수(1~100):표시이름:#색상 · 여러 개는 쉼표로 구분")).width(500)
                        .initial(inlineLegacyItem(effect.items, effect.itemName, effect.itemColor)).maxLength(2000).build());
                inputs.add(DialogInput.text("effect_take_items", Component.text("소모 형식: minecraft:아이템ID:개수 · 이름 확인 시 :표시이름:#색상 추가 · 여러 개는 쉼표로 구분")).width(500)
                        .initial(effect.takeItems).maxLength(2000).build());
                String custom = firstCustomReference(effect.items);
                inputs.add(DialogInput.singleOption("effect_custom_item", Component.text("저장한 특수 아이템 추가"),
                        customItemOptions(player, custom)).width(500).build());
                inputs.add(DialogInput.text("effect_custom_amount", Component.text("추가할 개수 (1~100)")).width(500)
                        .initial(Integer.toString(customReferenceAmount(effect.items, custom))).maxLength(3).build());
            }
        }
        ActionButton save = ActionButton.builder(Component.text("이 설정 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "save_effect"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 저장하고 설정 목록으로", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_effect"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        List<ActionButton> buttons = section.equals("ITEM") ? List.of(save,
                ActionButton.builder(Component.text("지급·소모 전부 삭제", NamedTextColor.RED)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "clear_effect_items"), BinaryTagHolder.binaryTagHolder("{}"))).build(), back)
                : List.of(save, back);
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(inputs).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private void openVariableHelp(Player player) {
        List<ActionButton> buttons = List.of(
                variableHelpButton("사용법 · 대사에 {{변수이름}} 입력"),
                variableHelpButton("저장 변수 · 변수 설정에서 이름=값 입력"),
                variableHelpButton("문자/Boolean · 이름=문자열, 완료=true 또는 false"),
                variableHelpButton("사칙연산 · 점수+=1, -=1, *=2, /=2"),
                variableHelpButton("난수 · damage_roll=random(5..20) · 5~20 정수 중 하나"),
                variableHelpButton("조건 비교 · 점수>=10, 이름!=값, 미설정=null"),
                variableHelpButton("조건 조합 · AND, OR, XOR, NOT"),
                variableHelpButton("채팅 저장 1 · 변수 이름만 입력 (예: nickname)"),
                variableHelpButton("채팅 저장 2 · 대사를 넘긴 뒤 다음 채팅 1회를 저장·숨김"),
                variableHelpButton("채팅 저장 3 · 이후 대사에 {{nickname}} 입력해 출력"),
                variableHelpButton("{{변수이름}} · 저장한 변수 값 출력"),
                variableHelpButton("{{player_name}} · 플레이어 이름"),
                variableHelpButton("{{player_uuid}} · 플레이어 UUID"),
                variableHelpButton("{{player_world}} · 현재 월드 이름"),
                variableHelpButton("{{player_x}}, {{player_y}}, {{player_z}} · 현재 좌표"),
                variableHelpButton("{{player_health}} · 현재 체력"),
                variableHelpButton("{{held_item_name}} · 손 아이템 이름 (없으면 아이템 ID)"),
                variableHelpButton("{{held_item_type}} · 손 아이템 ID"),
                variableHelpButton("{{held_item_amount}} · 손 아이템 개수"),
                variableHelpButton("{{skript.변수}} · Skript 연동 변수 출력"),
                variableHelpButton("Skript 키 · {rpgmaker::<UUID>::변수}"),
                variableHelpButton("Skript 표현식 · %player%, %player's location%"),
                variableHelpButton("Skript 전역 출력 · %{전역변수}%"),
                variableHelpButton("Skript 개인 변수 · %{quest::%uuid of player%}%"),
                variableHelpButton("조건·효과 · skript:quest::%uuid of player%"),
                ActionButton.builder(Component.text("← 전체 메뉴", NamedTextColor.GRAY)).width(250)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "variable_help_back"), BinaryTagHolder.binaryTagHolder("{}"))).build());
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("변수 도움말", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(1).build())));
    }

    private ActionButton variableHelpButton(String label) {
        return ActionButton.builder(Component.text(label, NamedTextColor.AQUA)).width(250)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "variable_help_info"), BinaryTagHolder.binaryTagHolder("{}"))).build();
    }

    private void openReturnEditor(Player player, boolean choiceMode) {
        effectChoiceMode.put(player.getUniqueId(), choiceMode);
        Effect effect = loadEffect(effectReadPath(player, choiceMode));
        String selected = returnOptions(player, null).stream()
                .filter(option -> option.value.equals(effect.returnTarget)).map(ReturnOption::label)
                .findFirst().orElse(effect.returnMode.equals("NONE") ? "설정 없음" : "삭제되었거나 빈 대상");
        List<ActionButton> buttons = List.of(
                returnButton("대사 페이지 선택", "pages", NamedTextColor.AQUA),
                returnButton("선택지 선택", "choices", NamedTextColor.GOLD),
                returnButton("복귀 설정 삭제", "none", NamedTextColor.RED),
                returnButton("← 기타 설정", "back", NamedTextColor.GRAY));
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(
                        "이전 진행으로 돌아가기 · " + limitText(selected, 45), NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private void openReturnTargetPicker(Player player, boolean choiceMode, String type) {
        List<ReturnOption> targets = returnOptions(player, type);
        if (targets.isEmpty()) {
            player.sendMessage(Component.text(type.equals("PAGE") ? "선택할 수 있는 대사 페이지가 없습니다." : "선택할 수 있는 선택지가 없습니다.", NamedTextColor.RED));
            openReturnEditor(player, choiceMode);
            return;
        }
        String current = loadEffect(effectReadPath(player, choiceMode)).returnTarget;
        DialogInput target = DialogInput.singleOption("return_target", Component.text(
                        type.equals("PAGE") ? "돌아갈 대사 페이지" : "돌아갈 선택지"),
                targets.stream().map(value -> option(value.value, value.label, current)).toList()).width(500).build();
        ActionButton save = ActionButton.builder(Component.text("이 대상으로 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "return_save"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 대상 종류 선택", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "return_open"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(
                        type.equals("PAGE") ? "대사 페이지 선택" : "선택지 선택", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(List.of(target)).build())
                .type(DialogType.multiAction(List.of(save, back)).columns(2).build())));
    }

    private ActionButton returnButton(String label, String action, NamedTextColor color) {
        return ActionButton.builder(Component.text(label, color)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "return_" + action), BinaryTagHolder.binaryTagHolder("{}"))).build();
    }

    private List<ReturnOption> returnOptions(Player player, String type) {
        String root = editorPath(player);
        List<String> pages = editorMessages(player);
        java.util.ArrayList<List<Choice>> choices = new java.util.ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            String path = root + ".page-choices." + page;
            if (!getConfig().contains(path) && page == pages.size() - 1) path = root;
            choices.add(loadChoices(path, player));
        }
        java.util.ArrayList<ReturnOption> result = new java.util.ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            String route = "p" + page;
            if ((type == null || type.equals("PAGE")) && !pages.get(page).isBlank())
                result.add(new ReturnOption("PAGE:" + route, "대사 " + (page + 1) + " · " + previewText(pages.get(page))));
            collectChoiceReturnOptions(result, type, choices.get(page), route, "대사 " + (page + 1));
        }
        return result;
    }

    private void collectChoiceReturnOptions(List<ReturnOption> result, String type, List<Choice> choices,
                                            String route, String breadcrumb) {
        for (int choiceIndex = 0; choiceIndex < choices.size(); choiceIndex++) {
            Choice choice = choices.get(choiceIndex);
            String choiceTrail = breadcrumb + " > 선택지 " + (choiceIndex + 1) + " · " + previewText(choice.label);
            if (type == null || type.equals("CHOICE"))
                result.add(new ReturnOption("CHOICE:" + route + "#c" + choiceIndex, choiceTrail));
            List<String> responsePages = splitPages(choice.response);
            for (int page = 0; page < responsePages.size(); page++) {
                String childRoute = route + "/c" + choiceIndex + "/p" + page;
                String pageTrail = choiceTrail + " > 후속 대사 " + (page + 1);
                if ((type == null || type.equals("PAGE")) && !responsePages.get(page).isBlank())
                    result.add(new ReturnOption("PAGE:" + childRoute, pageTrail + " · " + previewText(responsePages.get(page))));
                List<Choice> nested = page < choice.responseChoices.size() ? choice.responseChoices.get(page) : List.of();
                collectChoiceReturnOptions(result, type, nested, childRoute, pageTrail);
            }
        }
    }

    private String previewText(String text) {
        return limitText(text.replace('\n', ' ').strip(), 24);
    }

    private void saveEffectEditor(Player player, DialogResponseView response, boolean choiceMode) {
        String path = effectPath(player, choiceMode);
        switch (effectSection.getOrDefault(player.getUniqueId(), "ITEM")) {
            case "VARIABLE" -> {
                getConfig().set(path + ".chat-input-variable", response.getText("effect_chat_input_variable"));
                getConfig().set(path + ".variables-set", response.getText("effect_variables_set"));
                getConfig().set(path + ".variables-delete", response.getText("effect_variables_delete"));
                getConfig().set(path + ".variable", null);
                getConfig().set(path + ".value", null);
                getConfig().set(path + ".variable-action", null);
            }
            case "SOUND" -> {
                getConfig().set(path + ".sounds", response.getText("effect_sounds"));
                getConfig().set(path + ".sound", null);
                getConfig().set(path + ".sound-volume", null);
                getConfig().set(path + ".sound-pitch", null);
            }
            case "MESSAGE" -> {
                getConfig().set(path + ".message", response.getText("effect_message"));
                getConfig().set(path + ".message-color", response.getText("effect_message_color"));
            }
            case "COMMAND" -> {
                if (player.hasPermission("rpgmaker.admin")) {
                    getConfig().set(path + ".command", response.getText("effect_command"));
                    getConfig().set(path + ".command-target", response.getText("command_target"));
                }
            }
            default -> {
                String items = response.getText("effect_items");
                String custom = response.getText("effect_custom_item");
                if (custom != null && !custom.equals("NONE"))
                    items = putCustomReference(items, custom, response.getText("effect_custom_amount"));
                getConfig().set(path + ".items", items);
                getConfig().set(path + ".take-items", response.getText("effect_take_items"));
                getConfig().set(path + ".item", null);
                getConfig().set(path + ".amount", null);
                getConfig().set(path + ".take-item", null);
                getConfig().set(path + ".take-amount", null);
                getConfig().set(path + ".item-name", null);
                getConfig().set(path + ".item-color", null);
            }
        }
        if (choiceMode && editorChoicePage.getOrDefault(player.getUniqueId(), 0) == 0)
            getConfig().set(choicePath(player) + ".effect-" + editorChoice.getOrDefault(player.getUniqueId(), 1), null);
        saveConfig();
    }

    private String conditionPath(Player player, boolean choiceMode) {
        return choiceMode ? choicePath(player) + ".condition-" + editorChoice.getOrDefault(player.getUniqueId(), 1)
                : editorPath(player) + ".page-conditions." + editorPage.getOrDefault(player.getUniqueId(), 0);
    }

    private void openConditionHub(Player player, boolean choiceMode) {
        conditionChoiceMode.put(player.getUniqueId(), choiceMode);
        List<ActionButton> buttons = List.of(
                conditionButton("조건 조합 방식", "mode", NamedTextColor.GOLD),
                conditionButton("변수 조건 설정", "variable", NamedTextColor.AQUA),
                conditionButton("아이템 조건 설정", "item", NamedTextColor.GREEN),
                conditionButton("조건 만족 시 대사 변경", "text", NamedTextColor.YELLOW),
                conditionButton("← 기타 설정으로", "back", NamedTextColor.GRAY));
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("표시 조건 설정", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private ActionButton conditionButton(String label, String action, NamedTextColor color) {
        return ActionButton.builder(Component.text(label, color)).width(220)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "condition_" + action), BinaryTagHolder.binaryTagHolder("{}"))).build();
    }

    private void openConditionEditor(Player player, boolean choiceMode, String section) {
        conditionChoiceMode.put(player.getUniqueId(), choiceMode);
        conditionSection.put(player.getUniqueId(), section);
        String path = conditionPath(player, choiceMode);
        String type = getConfig().getString(path + ".type", "NONE");
        java.util.ArrayList<DialogInput> inputs = new java.util.ArrayList<>();
        String title;
        switch (section) {
            case "VARIABLE" -> {
                title = "변수 표시 조건";
                inputs.add(DialogInput.text("condition_variable", Component.text("기본 변수 · 예: affection")).width(500)
                        .initial(getConfig().getString(path + ".variable", "")).maxLength(100).build());
                inputs.add(DialogInput.text("condition_value", Component.text("일치해야 하는 값")).width(500)
                        .initial(getConfig().getString(path + ".value", "")).maxLength(500).build());
                String operator = getConfig().getString(path + ".operator", "EQ");
                inputs.add(DialogInput.singleOption("condition_operator", Component.text("기본 변수 비교 방식"), List.of(
                        option("EQ", "= · 같음", operator), option("NE", "!= · 다름", operator),
                        option("GT", "> · 큼", operator), option("GTE", ">= · 크거나 같음", operator),
                        option("LT", "< · 작음", operator), option("LTE", "<= · 작거나 같음", operator),
                        option("IS_SET", "값이 설정됨", operator), option("IS_UNSET", "값이 없음(null/none)", operator))).width(500).build());
                inputs.add(DialogInput.text("condition_extra_variables", Component.text("추가 변수 · 이름>=값, 이름2!=값2 (쉼표 구분)")).width(500)
                        .initial(getConfig().getString(path + ".extra-variables", "")).maxLength(1000).build());
                String logic = getConfig().getString(path + ".variable-logic", "AND");
                inputs.add(DialogInput.singleOption("condition_variable_logic", Component.text("여러 변수 관계"), List.of(
                        option("AND", "AND · 모두 일치", logic), option("OR", "OR · 하나 이상 일치", logic),
                        option("XOR", "XOR · 정확히 하나만 일치", logic), option("NOT", "NOT · 모두 불일치", logic))).width(500).build());
            }
            case "ITEM" -> {
                title = "아이템 표시 조건";
                inputs.add(DialogInput.text("condition_item_spec", Component.text("아이템:개수[:이름:색코드] · 이름 생략 시 종류·개수만 비교")).width(500)
                        .initial(loadCondition(path).itemSpec).maxLength(500).build());
                String custom = firstCustomReference(loadCondition(path).itemSpec);
                inputs.add(DialogInput.singleOption("condition_custom_item", Component.text("저장한 특수 아이템 불러오기"),
                        customItemOptions(player, custom)).width(500).build());
                inputs.add(DialogInput.text("condition_custom_amount", Component.text("필요 개수 (1~100)")).width(500)
                        .initial(Integer.toString(customReferenceAmount(loadCondition(path).itemSpec, custom))).maxLength(3).build());
            }
            case "TEXT" -> {
                title = "조건 만족 시 대사 변경";
                String[] replacement = getConfig().getString(path + ".replacement", "").split("\\n", -1);
                for (int i = 1; i <= MAXIMUM_LINES; i++) inputs.add(DialogInput.text("condition_replacement_" + i,
                                Component.text("변경 대사 " + i + "줄 · 표시 30자 · #FF0000:단어")).width(500)
                        .initial(limitLine(i <= replacement.length ? replacement[i - 1] : "")).maxLength(MAXIMUM_CHARACTERS_PER_LINE * 4).build());
            }
            default -> {
                title = "조건 조합 방식";
                inputs.add(DialogInput.singleOption("condition_type", Component.text("페이지/선택지를 표시할 조건"), List.of(
                        option("NONE", "조건 없음", type), option("VARIABLE", "변수 조건", type),
                        option("ITEM", "아이템 조건", type), option("BOTH", "변수와 아이템 모두", type),
                        option("ANY", "변수와 아이템 중 하나", type))).width(500).build());
            }
        }
        ActionButton save = ActionButton.builder(Component.text("이 조건 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "save_condition"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 저장하고 조건 목록으로", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_condition"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        List<ActionButton> buttons = section.equals("ITEM") ? List.of(save,
                ActionButton.builder(Component.text("아이템 조건 전부 삭제", NamedTextColor.RED)).width(200)
                        .action(DialogAction.customClick(Key.key("dialoguedisplay", "clear_condition_items"), BinaryTagHolder.binaryTagHolder("{}"))).build(), back)
                : List.of(save, back);
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(inputs).build()).type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private void saveConditionEditor(Player player, DialogResponseView response, boolean choiceMode) {
        String path = conditionPath(player, choiceMode);
        switch (conditionSection.getOrDefault(player.getUniqueId(), "MODE")) {
            case "VARIABLE" -> {
                getConfig().set(path + ".variable", response.getText("condition_variable"));
                getConfig().set(path + ".value", response.getText("condition_value"));
                getConfig().set(path + ".operator", response.getText("condition_operator"));
                getConfig().set(path + ".extra-variables", response.getText("condition_extra_variables"));
                getConfig().set(path + ".variable-logic", response.getText("condition_variable_logic"));
            }
            case "ITEM" -> {
                String custom = response.getText("condition_custom_item");
                getConfig().set(path + ".item-spec", custom != null && !custom.equals("NONE")
                        ? putCustomReference("", custom, response.getText("condition_custom_amount"))
                        : response.getText("condition_item_spec"));
                getConfig().set(path + ".item", null);
                getConfig().set(path + ".amount", null);
                getConfig().set(path + ".name-match", null);
                getConfig().set(path + ".item-name", null);
            }
            case "TEXT" -> {
                java.util.ArrayList<String> replacement = new java.util.ArrayList<>();
                for (int i = 1; i <= MAXIMUM_LINES; i++) replacement.add(limitLine(response.getText("condition_replacement_" + i)));
                while (replacement.size() > 1 && replacement.get(replacement.size() - 1).isBlank()) replacement.remove(replacement.size() - 1);
                getConfig().set(path + ".replacement", String.join("\n", replacement));
            }
            default -> getConfig().set(path + ".type", response.getText("condition_type"));
        }
        saveConfig();
    }

    private void openCameraEditor(Player player, boolean choiceMode) {
        effectChoiceMode.put(player.getUniqueId(), choiceMode);
        String direction = normalizeCameraDirection(getConfig().getString(editorPath(player) + ".camera-direction", "NORTH"));
        DialogInput input = DialogInput.singleOption("camera_direction", Component.text("대화 시작 시 바라볼 방향"), List.of(
                option("NORTH", "북쪽", direction), option("EAST", "동쪽", direction),
                option("SOUTH", "남쪽", direction), option("WEST", "서쪽", direction))).width(500).build();
        ActionButton save = ActionButton.builder(Component.text("카메라 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "save_camera"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 저장하고 설정 목록으로", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_camera"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("카메라 설정", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(List.of(input)).build()).type(DialogType.multiAction(List.of(save, back)).columns(2).build())));
    }

    private void openFlowEditor(Player player) {
        int page = editorPage.getOrDefault(player.getUniqueId(), 0);
        String path = editorPath(player) + ".page-flow." + page;
        Condition condition = loadCondition(path + ".condition");
        String timing = getConfig().getString(path + ".jump-timing", "AFTER");
        String terminal = Boolean.toString(getConfig().getBoolean(path + ".terminal", false));
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("flow_next_page", Component.text("다음 대사 번호"), pageNumberOptions(player,
                        getConfig().getInt(path + ".next-page", 0), "0 · 현재 번호+1")).width(500).build(),
                DialogInput.singleOption("flow_jump_target", Component.text("조건 만족 시 이동할 대사 번호"), pageNumberOptions(player,
                        getConfig().getInt(path + ".jump-target", 0), "0 · 조건 이동 사용 안 함")).width(500).build(),
                DialogInput.singleOption("flow_jump_timing", Component.text("조건 이동 시점"), List.of(
                        option("BEFORE", "해당 대사 재생 전 이동", timing),
                        option("AFTER", "해당 대사 재생 후 이동", timing))).width(500).build(),
                DialogInput.singleOption("flow_terminal", Component.text("종결 대사 여부"), List.of(
                        option("false", "아니요 · 다음 흐름 계속", terminal),
                        option("true", "예 · 이 대사 종료 시 시퀀스 종료", terminal))).width(500).build(),
                DialogInput.singleOption("flow_condition_type", Component.text("이동·종결 적용 조건"), List.of(
                        option("NONE", "조건 없음 · 항상 적용", condition.type), option("VARIABLE", "변수 조건", condition.type),
                        option("ITEM", "아이템 조건", condition.type), option("BOTH", "변수와 아이템 모두", condition.type),
                        option("ANY", "변수와 아이템 중 하나", condition.type))).width(500).build(),
                DialogInput.text("flow_variable_checks", Component.text("변수 체크 · affection>=10, done=true, missing=null")).width(500)
                        .initial(condition.extraVariables).maxLength(1500).build(),
                DialogInput.singleOption("flow_variable_logic", Component.text("여러 변수 관계"), List.of(
                        option("AND", "AND · 모두", condition.variableLogic), option("OR", "OR · 하나 이상", condition.variableLogic),
                        option("XOR", "XOR · 정확히 하나", condition.variableLogic), option("NOT", "NOT · 모두 불일치", condition.variableLogic))).width(500).build(),
                DialogInput.text("flow_item_spec", Component.text("아이템 체크 · minecraft:아이템ID:개수[:이름:#색상]")).width(500)
                        .initial(condition.itemSpec).maxLength(1000).build());
        ActionButton save = ActionButton.builder(Component.text("흐름 저장", NamedTextColor.GREEN)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "save_flow"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        ActionButton back = ActionButton.builder(Component.text("← 저장하고 기타 설정", NamedTextColor.GRAY)).width(200)
                .action(DialogAction.customClick(Key.key("dialoguedisplay", "back_flow"), BinaryTagHolder.binaryTagHolder("{}"))).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text(
                        "대사 " + (page + 1) + " 흐름 설정", NamedTextColor.GOLD))
                .pause(false).canCloseWithEscape(true).inputs(inputs).build()).type(DialogType.multiAction(List.of(save, back)).columns(2).build())));
    }

    private void saveFlowEditor(Player player, DialogResponseView response) {
        int page = editorPage.getOrDefault(player.getUniqueId(), 0);
        int maximum = editorMessages(player).size();
        String path = editorPath(player) + ".page-flow." + page;
        getConfig().set(path + ".next-page", safePageNumber(response.getText("flow_next_page"), maximum));
        getConfig().set(path + ".jump-target", safePageNumber(response.getText("flow_jump_target"), maximum));
        getConfig().set(path + ".jump-timing", response.getText("flow_jump_timing"));
        getConfig().set(path + ".terminal", Boolean.parseBoolean(response.getText("flow_terminal")));
        String condition = path + ".condition";
        getConfig().set(condition + ".type", response.getText("flow_condition_type"));
        getConfig().set(condition + ".variable", "");
        getConfig().set(condition + ".value", "");
        getConfig().set(condition + ".operator", "EQ");
        getConfig().set(condition + ".extra-variables", response.getText("flow_variable_checks"));
        getConfig().set(condition + ".variable-logic", response.getText("flow_variable_logic"));
        getConfig().set(condition + ".item-spec", response.getText("flow_item_spec"));
        saveConfig();
    }

    private String choiceEffectPath(Player player) {
        return choicePath(player) + ".response-effects-" + editorChoice.getOrDefault(player.getUniqueId(), 1)
                + "." + editorChoicePage.getOrDefault(player.getUniqueId(), 0);
    }

    private List<String> editorMessages(Player player) {
        String path = editorPath(player);
        List<String> saved = getConfig().getStringList(path + ".message-pages");
        if (!saved.isEmpty()) return new java.util.ArrayList<>(saved);
        String legacy = getConfig().getString(path + ".message", "대화 내용");
        if (legacy.startsWith("[B@")) legacy = "대화 내용";
        return new java.util.ArrayList<>(List.of(legacy));
    }

    private void saveEditorMessage(Player player, String message, boolean keepBlankForNavigation) {
        List<String> messages = editorMessages(player);
        int index = Math.min(editorPage.getOrDefault(player.getUniqueId(), 0), messages.size() - 1);
        if (message.isBlank() && messages.size() > 1 && !keepBlankForNavigation) {
            messages.remove(index);
            shiftPageMetadata(editorPath(player), index, messages.size());
            editorPage.put(player.getUniqueId(), Math.min(index, messages.size() - 1));
        } else {
            messages.set(index, message);
        }
        String path = editorPath(player);
        getConfig().set(path + ".message-pages", messages);
        getConfig().set(path + ".message", null);
    }

    private void shiftPageMetadata(String root, int removed, int newSize) {
        for (String section : List.of("page-choices", "page-effects", "page-conditions", "page-portraits", "page-expressions",
                "page-show-portraits", "page-show-speakers", "page-speakers", "page-flow")) {
            for (int page = removed; page < newSize; page++) {
                String source = root + "." + section + "." + (page + 1);
                String destination = root + "." + section + "." + page;
                if (section.equals("page-show-portraits") || section.equals("page-show-speakers") || section.equals("page-speakers"))
                    getConfig().set(destination, getConfig().get(source));
                else copySection(source, destination);
            }
            getConfig().set(root + "." + section + "." + newSize, null);
        }
    }

    private void deleteEditorPage(Player player) {
        List<String> pages = editorMessages(player);
        int page = Math.min(editorPage.getOrDefault(player.getUniqueId(), 0), pages.size() - 1);
        String path = editorPath(player);
        if (pages.size() == 1) {
            pages.set(0, "");
            for (String section : List.of("page-choices", "page-effects", "page-conditions", "page-portraits", "page-expressions",
                    "page-show-portraits", "page-show-speakers", "page-speakers", "page-flow"))
                getConfig().set(path + "." + section + ".0", null);
        } else {
            pages.remove(page);
            shiftPageMetadata(path, page, pages.size());
            editorPage.put(player.getUniqueId(), Math.min(page, pages.size() - 1));
        }
        getConfig().set(path + ".message-pages", pages);
        getConfig().set(path + ".message", null);
        saveConfig();
    }


    private String readLines(DialogResponseView response) {
        int maximumLines = MAXIMUM_LINES;
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= maximumLines; i++) {
            String line = response.getText("line_" + i);
            lines.add(limitLine(line));
        }
        while (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return String.join("\n", lines);
    }

    private List<String> choiceResponsePages(Player player, int choice) {
        String path = choicePath(player);
        List<String> pages = getConfig().getStringList(path + ".response-pages-" + choice);
        if (!pages.isEmpty()) return new java.util.ArrayList<>(pages);
        return new java.util.ArrayList<>(List.of(getConfig().getString(path + ".response-" + choice, "")));
    }

    private void saveChoiceResponse(Player player, int choice, String message) {
        List<String> pages = choiceResponsePages(player, choice);
        int page = Math.min(editorChoicePage.getOrDefault(player.getUniqueId(), 0), pages.size() - 1);
        pages.set(page, message);
        String path = choicePath(player);
        getConfig().set(path + ".response-pages-" + choice, pages);
        getConfig().set(path + ".response-" + choice, null);
    }

    private void deleteChoicePage(Player player) {
        int choice = editorChoice.getOrDefault(player.getUniqueId(), 1);
        List<String> pages = choiceResponsePages(player, choice);
        int page = Math.min(editorChoicePage.getOrDefault(player.getUniqueId(), 0), pages.size() - 1);
        String path = choicePath(player);
        List<String> sections = List.of("response-effects-", "response-portrait-", "response-expression-",
                "response-show-portraits-", "response-show-speakers-", "response-page-choices-");
        if (pages.size() == 1) {
            pages.set(0, "");
            for (String section : sections) getConfig().set(path + "." + section + choice + ".0", null);
        } else {
            pages.remove(page);
            for (String section : sections) {
                String root = path + "." + section + choice;
                for (int index = page; index < pages.size(); index++) copySection(root + "." + (index + 1), root + "." + index);
                getConfig().set(root + "." + pages.size(), null);
            }
            editorChoicePage.put(player.getUniqueId(), Math.min(page, pages.size() - 1));
        }
        getConfig().set(path + ".response-pages-" + choice, pages);
        getConfig().set(path + ".response-" + choice, null);
        saveConfig();
    }

    private void saveChoicePortrait(Player player, DialogResponseView response, int choice) {
        int page = editorChoicePage.getOrDefault(player.getUniqueId(), 0);
        String path = choicePath(player);
        String character = response.getText("choice_character");
        if (character != null) getConfig().set(path + ".response-portrait-" + choice + "." + page,
                resolvePortrait(character, response.getText("choice_gender")));
        String expression = response.getText("choice_expression");
        if (expression != null) getConfig().set(path + ".response-expression-" + choice + "." + page, expression);
    }

    private void deleteChoice(Player player, int choice) {
        String path = choicePath(player);
        int count = Math.min(8, getConfig().getInt(path + ".choice-count", 0));
        for (int i = choice; i < count; i++) {
            getConfig().set(path + ".choice-" + i, getConfig().getString(path + ".choice-" + (i + 1), ""));
            getConfig().set(path + ".response-pages-" + i, choiceResponsePages(player, i + 1));
            getConfig().set(path + ".response-" + i, null);
            copySection(path + ".response-portrait-" + (i + 1), path + ".response-portrait-" + i);
            copySection(path + ".response-expression-" + (i + 1), path + ".response-expression-" + i);
            copySection(path + ".response-show-portraits-" + (i + 1), path + ".response-show-portraits-" + i);
            copySection(path + ".response-show-speakers-" + (i + 1), path + ".response-show-speakers-" + i);
            copySection(path + ".response-page-choices-" + (i + 1), path + ".response-page-choices-" + i);
            getConfig().set(path + ".response-effects-" + i, null);
            int responsePages = choiceResponsePages(player, i + 1).size();
            for (int page = 0; page < responsePages; page++)
                for (String key : List.of("items", "take-items", "variables-set", "variables-delete", "sounds", "message", "message-color", "return-mode", "return-target",
                        "item", "amount", "take-item", "take-amount", "item-name", "item-color", "variable", "value", "variable-action",
                        "sound", "sound-volume", "sound-pitch", "command", "command-target"))
                    getConfig().set(path + ".response-effects-" + i + "." + page + "." + key,
                            getConfig().get(path + ".response-effects-" + (i + 1) + "." + page + "." + key));
            for (String key : List.of("items", "take-items", "variables-set", "variables-delete", "sounds", "message", "message-color", "return-mode", "return-target",
                    "item", "amount", "take-item", "take-amount", "item-name", "item-color", "variable", "value", "variable-action",
                    "sound", "sound-volume", "sound-pitch", "command", "command-target"))
                getConfig().set(path + ".effect-" + i + "." + key, getConfig().get(path + ".effect-" + (i + 1) + "." + key));
            getConfig().set(path + ".end-" + i, getConfig().getBoolean(path + ".end-" + (i + 1), false));
            getConfig().set(path + ".target-page-" + i, getConfig().getInt(path + ".target-page-" + (i + 1), 0));
            getConfig().set(path + ".speaker-" + i, getConfig().getString(path + ".speaker-" + (i + 1), ""));
            for (String key : List.of("type", "variable", "value", "operator", "extra-variables", "variable-logic", "item-spec", "item", "amount", "item-name", "name-match", "replacement"))
                getConfig().set(path + ".condition-" + i + "." + key, getConfig().get(path + ".condition-" + (i + 1) + "." + key));
        }
        getConfig().set(path + ".choice-" + count, null);
        getConfig().set(path + ".response-" + count, null);
        getConfig().set(path + ".response-pages-" + count, null);
        getConfig().set(path + ".response-portrait-" + count, null);
        getConfig().set(path + ".response-expression-" + count, null);
        getConfig().set(path + ".response-show-portraits-" + count, null);
        getConfig().set(path + ".response-show-speakers-" + count, null);
        getConfig().set(path + ".response-page-choices-" + count, null);
        getConfig().set(path + ".effect-" + count, null);
        getConfig().set(path + ".response-effects-" + count, null);
        getConfig().set(path + ".end-" + count, null);
        getConfig().set(path + ".target-page-" + count, null);
        getConfig().set(path + ".speaker-" + count, null);
        getConfig().set(path + ".condition-" + count, null);
        getConfig().set(path + ".choice-count", Math.max(0, count - 1));
        saveConfig();
    }

    private String editorPath(Player player) {
        return personalDialoguePath(player, editorName.getOrDefault(player.getUniqueId(), "default"));
    }

    private String personalDialoguePath(Player player, String name) {
        return "player-dialogues." + editorOwner.getOrDefault(player.getUniqueId(), player.getUniqueId()) + "." + sanitizeName(name);
    }

    private String dialoguePath(Player player, String name) {
        String personal = personalDialoguePath(player, name);
        if (getConfig().contains(personal)) return personal;
        String published = "public-dialogues." + sanitizeName(name);
        if (getConfig().contains(published)) return published;
        String legacy = "dialogues." + sanitizeName(name);
        return getConfig().contains(legacy) && (player.hasPermission("rpgmaker.admin")
                || sanitizeName(name).equals("default")) ? legacy : personal;
    }

    private List<String> dialogueNames(Player player) {
        ensureExamples(player);
        Set<String> names = new HashSet<>();
        var personal = getConfig().getConfigurationSection("player-dialogues." + player.getUniqueId());
        if (personal != null) names.addAll(personal.getKeys(false));
        if (player.hasPermission("rpgmaker.admin")) {
            var legacy = getConfig().getConfigurationSection("dialogues");
            if (legacy != null) names.addAll(legacy.getKeys(false));
        }
        return names.stream().sorted(java.util.Comparator
                .comparing((String name) -> !isBuiltInExample(personalDialoguePath(player, name)))
                .thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private List<String> publicDialogueNames() {
        var section = getConfig().getConfigurationSection("public-dialogues");
        return section == null ? List.of() : section.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void ensureExamples(Player player) {
        boolean changed = false;
        List<String> dismissed = getConfig().getStringList("dismissed-examples." + player.getUniqueId());
        String obsolete = personalDialoguePath(player, "초보_상점_이용법_v2");
        if (isBuiltInExample(obsolete)) {
            getConfig().set(obsolete, null);
            changed = true;
        }
        changed |= ensureExampleItems(player);
        for (var entry : Map.of("romance-final", "달빛_아래_피어난_약속", "shop-final", "초보_상점_이용법",
                "skript-final", "Skript_변수_연동_테스트").entrySet()) {
            String source = "example-templates." + entry.getKey();
            String destination = personalDialoguePath(player, entry.getValue());
            int version = getConfig().getInt(source + ".example-version", 1);
            boolean upgrade = isBuiltInExample(destination) && getConfig().getInt(destination + ".example-version", 0) < version;
            if (!dismissed.contains(sanitizeName(entry.getValue())) && (!getConfig().contains(destination) || upgrade) && getConfig().contains(source)) {
                copySection(source, destination);
                getConfig().set(destination + ".built-in-example", true);
                replaceOwnerReferences(destination, player.getUniqueId());
                changed = true;
            }
        }
        if (changed) saveConfig();
    }

    private void installBundledExamples() {
        try (var reader = new java.io.InputStreamReader(java.util.Objects.requireNonNull(getResource("config.yml")), java.nio.charset.StandardCharsets.UTF_8)) {
            var bundled = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            int bundledVersion = bundled.getInt("examples-version", 0);
            Object savedVersion = getConfig().getValues(false).get("examples-version");
            int currentVersion = savedVersion instanceof Number number ? number.intValue() : 0;
            if (currentVersion >= bundledVersion) return;
            for (String root : List.of("example-templates", "example-item-templates")) {
                getConfig().set(root, null);
                var section = bundled.getConfigurationSection(root);
                if (section != null) section.getValues(true).forEach((key, value) -> {
                    if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) getConfig().set(root + "." + key, value);
                });
            }
            getConfig().set("examples-version", bundledVersion);
            saveConfig();
        } catch (Exception error) {
            getLogger().warning("Bundled examples could not be updated: " + error.getMessage());
        }
    }

    private void installPublicGuideDialogue() {
        String destination = "public-dialogues." + sanitizeName("초보_상점_이용법");
        if (getConfig().contains(destination)) return;
        String source = "example-templates.shop-final";
        if (!getConfig().contains(source)) return;
        copySection(source, destination);
        getConfig().set(destination + ".public-owner", SYSTEM_PUBLIC_OWNER.toString());
        getConfig().set(destination + ".public-by", "RPGMaker");
        saveConfig();
    }

    private boolean ensureExampleItems(Player player) {
        boolean changed = false;
        var templates = getConfig().getConfigurationSection("example-item-templates");
        if (templates == null) return false;
        for (String name : templates.getKeys(false)) {
            String source = "example-item-templates." + name;
            String destination = customItemRoot(player.getUniqueId()) + "." + name;
            int version = getConfig().getInt(source + ".example-version", 1);
            if (!getConfig().contains(destination) || getConfig().getBoolean(destination + ".built-in-example", false)
                    && getConfig().getInt(destination + ".example-version", 0) < version) {
                copySection(source, destination);
                getConfig().set(destination + ".built-in-example", true);
                changed = true;
            }
        }
        return changed;
    }

    private void replaceOwnerReferences(String path, UUID owner) {
        var section = getConfig().getConfigurationSection(path);
        if (section == null) return;
        section.getValues(true).forEach((key, value) -> {
            if (value instanceof String text && text.contains("@OWNER/"))
                getConfig().set(path + "." + key, text.replace("@OWNER/", "@" + owner + "/"));
            else if (value instanceof List<?> list && list.stream().anyMatch(item -> item instanceof String text && text.contains("@OWNER/")))
                getConfig().set(path + "." + key, list.stream().map(item -> item instanceof String text
                        ? text.replace("@OWNER/", "@" + owner + "/") : item).toList());
        });
    }

    private void dismissExample(Player player, String name) {
        String path = "dismissed-examples." + player.getUniqueId();
        java.util.ArrayList<String> dismissed = new java.util.ArrayList<>(getConfig().getStringList(path));
        String sanitized = sanitizeName(name);
        if (!dismissed.contains(sanitized)) dismissed.add(sanitized);
        getConfig().set(path, dismissed);
    }

    private boolean isBuiltInExample(String path) {
        return getConfig().getBoolean(path + ".built-in-example", false);
    }

    private List<String> adminDialogueNames(String root) {
        var section = getConfig().getConfigurationSection(root);
        if (section == null) return List.of();
        return section.getKeys(false).stream().filter(name -> !isBuiltInExample(root + "." + name))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void prepareEditor(Player player, String rawName) {
        choiceRootOverride.remove(player.getUniqueId());
        choiceParent.remove(player.getUniqueId());
        String name = sanitizeName(rawName);
        String personal = personalDialoguePath(player, name);
        String source = dialoguePath(player, name);
        if (!source.equals(personal) && getConfig().contains(source)) {
            copySection(source, personal);
            saveConfig();
        }
        editorName.put(player.getUniqueId(), name);
        editorPage.put(player.getUniqueId(), 0);
    }

    private void copySection(String source, String destination) {
        var section = getConfig().getConfigurationSection(source);
        getConfig().set(destination, null);
        if (section != null) section.getValues(true).forEach((key, value) -> {
            if (!(value instanceof org.bukkit.configuration.ConfigurationSection))
                getConfig().set(destination + "." + key, value);
        });
    }

    private String choicePath(Player player) {
        String override = choiceRootOverride.get(player.getUniqueId());
        if (override != null) return override;
        String root = editorPath(player);
        int page = editorPage.getOrDefault(player.getUniqueId(), 0);
        String path = root + ".page-choices." + page;
        int legacyCount = Math.min(8, getConfig().getInt(root + ".choice-count", 0));
        if (!getConfig().contains(path) && page == editorMessages(player).size() - 1 && legacyCount > 0) {
            getConfig().set(path + ".choice-count", legacyCount);
            for (int i = 1; i <= legacyCount; i++) {
                getConfig().set(path + ".choice-" + i, getConfig().getString(root + ".choice-" + i, ""));
                List<String> pages = getConfig().getStringList(root + ".response-pages-" + i);
                if (pages.isEmpty()) pages = List.of(getConfig().getString(root + ".response-" + i, ""));
                getConfig().set(path + ".response-pages-" + i, pages);
                getConfig().set(path + ".end-" + i, getConfig().getBoolean(root + ".end-" + i, false));
            }
            getConfig().set(root + ".choice-count", null);
            for (int i = 1; i <= legacyCount; i++) {
                getConfig().set(root + ".choice-" + i, null);
                getConfig().set(root + ".response-" + i, null);
                getConfig().set(root + ".response-pages-" + i, null);
                getConfig().set(root + ".end-" + i, null);
            }
            saveConfig();
        }
        return path;
    }

    private String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) return "default";
        String clean = raw.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return clean.isBlank() ? "default" : clean;
    }

    private void show(Player player, String speaker, String message, List<Choice> choices) {
        show(player, speaker, message, choices, "NORTH", getConfig().getDouble("distance", 1.8));
    }

    private void show(Player player, String speaker, String message, List<Choice> choices,
                      String cameraDirection, double dialogueDistance) {
        show(player, speaker, message, choices, cameraDirection, dialogueDistance, true, true);
    }

    private void show(Player player, String speaker, String message, List<Choice> choices,
                      String cameraDirection, double dialogueDistance, boolean showPortrait) {
        show(player, speaker, message, choices, cameraDirection, dialogueDistance, showPortrait, showPortrait);
    }

    private void show(Player player, String speaker, String message, List<Choice> choices,
                      String cameraDirection, double dialogueDistance, boolean showPortrait, boolean showSpeaker) {
        close(player);
        String resolvedSpeaker = expandVariables(player, speaker);
        List<String> pages = splitPages(message);
        String resolvedMessage = expandDialogueText(player, pages.get(0));
        int originalHeldSlot = player.getInventory().getHeldItemSlot();
        ItemStack originalMainHandItem = player.getInventory().getItem(originalHeldSlot);
        if (originalMainHandItem != null) originalMainHandItem = originalMainHandItem.clone();
        ItemStack originalNinthItem = player.getInventory().getItem(8);
        if (originalNinthItem != null) originalNinthItem = originalNinthItem.clone();
        float lockedYaw = player.getLocation().getYaw();
        float lockedPitch = player.getLocation().getPitch();
        Location origin = player.getEyeLocation();
        TextDisplay frame = spawn(player, origin,
                Component.text("\uE000").font(Key.key("dialog", "frame")));
        frame.setTransformation(scale((float) getConfig().getDouble("frame-scale", 0.16)));
        TextDisplay choiceFrame = spawn(player, origin, Component.empty());
        choiceFrame.setTextOpacity((byte) 247);
        TextDisplay portrait = spawn(player, origin, Component.text("\uE001").font(Key.key("dialog", "portrait")));
        portrait.setTextOpacity((byte) 249);
        TextDisplay speakerDisplay = spawn(player, origin, formattedText(fixedSpeakerText(resolvedSpeaker), NamedTextColor.GOLD));
        speakerDisplay.setTextOpacity((byte) 248);
        speakerDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
        speakerDisplay.setLineWidth(1024);
        TextDisplay[] bodyLines = {spawn(player, origin, Component.empty())};
        bodyLines[0].setTextOpacity((byte) 248);
        bodyLines[0].setAlignment(TextDisplay.TextAlignment.LEFT);
        bodyLines[0].setLineWidth(1024);
        TextDisplay choiceDisplay = spawn(player, origin, Component.empty());
        choiceDisplay.setTextOpacity((byte) 248);
        choiceDisplay.setAlignment(TextDisplay.TextAlignment.LEFT);
        choiceDisplay.setLineWidth(1024);
        choiceDisplay.setTransformation(scale((float) getConfig().getDouble("choice-scale", 0.58)));
        Dialogue dialogue = new Dialogue(player, frame, choiceFrame, portrait, speakerDisplay, bodyLines, choiceDisplay, showPortrait, showSpeaker,
                resolvedSpeaker, message, choices, lockedYaw, lockedPitch, dialogueDistance,
                originalHeldSlot, originalNinthItem, originalMainHandItem);
        dialogue.pages = pages;
        dialogue.message = resolvedMessage;
        dialogue.pageChoices = new java.util.ArrayList<>();
        for (int i = 0; i < dialogue.pages.size(); i++) dialogue.pageChoices.add(List.of());
        dialogue.pageEffects = new java.util.ArrayList<>();
        for (int i = 0; i < dialogue.pages.size(); i++) dialogue.pageEffects.add(Effect.NONE);
        dialogue.pageConditions = new java.util.ArrayList<>();
        for (int i = 0; i < dialogue.pages.size(); i++) dialogue.pageConditions.add(Condition.NONE);
        dialogue.pageRoutes = new java.util.ArrayList<>();
        for (int i = 0; i < dialogue.pages.size(); i++) dialogue.pageRoutes.add(PageRoute.DEFAULT);
        dialogue.pagePortraits = new java.util.ArrayList<>();
        dialogue.pageExpressions = new java.util.ArrayList<>();
        dialogue.pagePortraitVisible = new java.util.ArrayList<>();
        dialogue.pageSpeakerVisible = new java.util.ArrayList<>();
        for (int i = 0; i < dialogue.pages.size(); i++) {
            dialogue.pagePortraits.add("SENTINEL");
            dialogue.pageExpressions.add("HAPPY");
            dialogue.pagePortraitVisible.add(true);
            dialogue.pageSpeakerVisible.add(true);
        }
        if (!choices.isEmpty()) dialogue.pageChoices.set(dialogue.pages.size() - 1, choices);
        backupDialogueHotbar(player, originalHeldSlot, originalNinthItem);
        player.getInventory().setItem(8, transparentHandItem());
        player.getInventory().setHeldItemSlot(8);
        active.put(player.getUniqueId(), dialogue);
        updatePortrait(dialogue);
        applyScales(dialogue);
        position(dialogue);
    }

    private float directionYaw(String direction) {
        return switch (normalizeCameraDirection(direction)) {
            case "NORTH" -> 180.0f;
            case "EAST" -> -90.0f;
            case "SOUTH" -> 0.0f;
            case "WEST" -> 90.0f;
            default -> 180.0f;
        };
    }

    private ItemStack transparentHandItem() {
        ItemStack item = new ItemStack(Material.STICK);
        item.editMeta(meta -> {
            meta.setItemModel(new NamespacedKey(this, "invisible_hand"));
            meta.displayName(Component.empty());
            meta.getPersistentDataContainer().set(temporaryHandKey, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    private void backupDialogueHotbar(Player player, int heldSlot, ItemStack item) {
        var data = player.getPersistentDataContainer();
        data.set(hotbarItemKey, PersistentDataType.BYTE_ARRAY,
                item == null || item.getType().isAir() ? new byte[0] : item.serializeAsBytes());
        data.set(hotbarSlotKey, PersistentDataType.INTEGER, heldSlot);
        player.saveData();
    }

    private void restoreDialogueHotbar(Player player) {
        var data = player.getPersistentDataContainer();
        byte[] saved = data.get(hotbarItemKey, PersistentDataType.BYTE_ARRAY);
        Integer heldSlot = data.get(hotbarSlotKey, PersistentDataType.INTEGER);
        if (saved == null) return;
        restoreDialogueHotbar(player, saved.length == 0 ? null : ItemStack.deserializeBytes(saved),
                heldSlot == null ? 0 : heldSlot);
    }

    private void restoreDialogueHotbar(Player player, ItemStack fallbackItem, int fallbackSlot) {
        var data = player.getPersistentDataContainer();
        byte[] saved = data.get(hotbarItemKey, PersistentDataType.BYTE_ARRAY);
        Integer savedSlot = data.get(hotbarSlotKey, PersistentDataType.INTEGER);
        ItemStack restored = fallbackItem;
        if (saved != null) try {
            restored = saved.length == 0 ? null : ItemStack.deserializeBytes(saved);
        } catch (IllegalArgumentException exception) {
            getLogger().warning(player.getName() + "의 9번 슬롯 저장본을 읽지 못해 메모리 사본을 복구합니다.");
        }
        ItemStack current = player.getInventory().getItem(8);
        if (current != null && !current.getType().isAir() && !isTemporaryHand(current)) {
            player.getInventory().addItem(current).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        player.getInventory().setItem(8, restored);
        player.getInventory().setHeldItemSlot(savedSlot == null ? fallbackSlot : savedSlot);
        data.remove(hotbarItemKey);
        data.remove(hotbarSlotKey);
        player.saveData();
    }

    private boolean isTemporaryHand(ItemStack item) {
        return item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(temporaryHandKey, PersistentDataType.BOOLEAN, false);
    }

    private String normalizeCameraDirection(String direction) {
        return List.of("NORTH", "EAST", "SOUTH", "WEST").contains(direction) ? direction : "NORTH";
    }

    private void applyScales(Dialogue d) {
        float uiScale = (float) effectiveUiScale(d);
        float oldFrameScale = (float) layout(d, "frame-scale", 0.22);
        d.frame.setTransformation(scale(
                (float) layout(d, "frame-scale-x", oldFrameScale) * uiScale,
                (float) layout(d, "frame-scale-y", oldFrameScale) * uiScale));
        d.choiceFrame.setTransformation(scale(
                (float) layout(d, "choice-frame-scale-x", choiceFrameScaleDefault(d, "x")) * uiScale,
                (float) layout(d, "choice-frame-scale-y", choiceFrameScaleDefault(d, "y")) * uiScale));
        if (d.showPortrait) d.portrait.setTransformation(scale((float) layout(d, "portrait-scale", 0.24) * uiScale));
        if (d.showSpeaker) d.speakerDisplay.setTransformation(scale((float) layout(d, "speaker-scale", 0.68) * uiScale));
        d.bodyLines[0].setTransformation(scale((float) layout(d, "text-scale", DEFAULT_TEXT_SIZE / 100.0) * uiScale));
        d.choiceDisplay.setTransformation(scale((float) layout(d, "choice-scale", 0.60) * uiScale));
    }

    private double layout(Dialogue dialogue, String key, double fallback) {
        return getConfig().getDouble(layoutPrefix(portraitVisible(dialogue), speakerVisible(dialogue)) + key, fallback);
    }

    static String layoutPrefix(boolean showPortrait, boolean showSpeaker) {
        if (showPortrait && showSpeaker) return "";
        if (!showPortrait && !showSpeaker) return "plain-";
        return showSpeaker ? "speaker-only-" : "portrait-only-";
    }

    private double choiceFrameScaleDefault(Dialogue dialogue, String axis) {
        double oldFrameScale = layout(dialogue, "frame-scale", 0.22);
        return layout(dialogue, "frame-scale-" + axis, oldFrameScale) * 0.5;
    }

    private double lineLayoutDefault(Dialogue dialogue, String key) {
        if (key.endsWith("-scale")) return layout(dialogue, "text-scale", DEFAULT_TEXT_SIZE / 100.0);
        if (key.endsWith("-vertical-offset")) {
            int line = Character.digit(key.charAt("text-line-".length()), 10);
            return (2.5 - line) * 0.10;
        }
        return 0.0;
    }

    private void editorControls(Player player) {
        Dialogue dialogue = active.get(player.getUniqueId());
        boolean showPortrait = dialogue == null || dialogue.showPortrait;
        boolean showSpeaker = dialogue == null || dialogue.showSpeaker;
        player.sendMessage(Component.text("[다이얼로그 배치 편집]", NamedTextColor.GOLD));
        editorFrameRow(player);
        if (showPortrait) editorRow(player, "캐릭터", "portrait-x-offset", "portrait-vertical-offset", "portrait-scale");
        if (showSpeaker) editorRow(player, "화자 이름", "speaker-x-offset", "speaker-vertical-offset", "speaker-scale");
        editorRow(player, "본문", "text-x-offset", "text-vertical-offset", "text-scale");
        player.sendMessage(Component.text("본문 최대 4줄 · 줄당 공백 포함 30자", NamedTextColor.YELLOW));
        editorRow(player, "선택지", "choice-x-offset", "choice-vertical-offset", "choice-scale");
        editorChoiceFrameRows(player);
        player.sendMessage(button("[저장]", "/rpgmaker save", NamedTextColor.GREEN)
                .append(Component.text("  ")).append(button("[편집 종료]", "/rpgmaker close", NamedTextColor.RED)));
    }

    private void editorFrameRow(Player player) {
        player.sendMessage(Component.text("외곽선 위치  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust frame-x-offset -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust frame-x-offset 0.03", NamedTextColor.AQUA))
                .append(button("  ↑", "/rpgmaker adjust vertical-offset 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust vertical-offset -0.03", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("외곽선 크기  ", NamedTextColor.YELLOW)
                .append(button("[폭＋]", "/rpgmaker adjust frame-scale-x 0.02", NamedTextColor.GOLD))
                .append(button(" [폭－]", "/rpgmaker adjust frame-scale-x -0.02", NamedTextColor.GOLD))
                .append(button(" [높이＋]", "/rpgmaker adjust frame-scale-y 0.02", NamedTextColor.LIGHT_PURPLE))
                .append(button(" [높이－]", "/rpgmaker adjust frame-scale-y -0.02", NamedTextColor.LIGHT_PURPLE)));
    }

    private void editorChoiceFrameRows(Player player) {
        player.sendMessage(Component.text("소형 박스 위치  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust choice-frame-x-offset -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust choice-frame-x-offset 0.03", NamedTextColor.AQUA))
                .append(button("  ↑", "/rpgmaker adjust choice-frame-vertical-offset 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust choice-frame-vertical-offset -0.03", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("소형 박스 크기  ", NamedTextColor.YELLOW)
                .append(button("[전체＋]", "/rpgmaker adjust choice-frame-scale 0.02", NamedTextColor.GREEN))
                .append(button(" [전체－]", "/rpgmaker adjust choice-frame-scale -0.02", NamedTextColor.GREEN))
                .append(button(" [폭＋]", "/rpgmaker adjust choice-frame-scale-x 0.02", NamedTextColor.GOLD))
                .append(button(" [폭－]", "/rpgmaker adjust choice-frame-scale-x -0.02", NamedTextColor.GOLD))
                .append(button(" [높이＋]", "/rpgmaker adjust choice-frame-scale-y 0.02", NamedTextColor.LIGHT_PURPLE))
                .append(button(" [높이－]", "/rpgmaker adjust choice-frame-scale-y -0.02", NamedTextColor.LIGHT_PURPLE)));
    }

    private void editorRow(Player player, String name, String x, String y, String scale) {
        player.sendMessage(Component.text(name + "  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust " + x + " -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust " + x + " 0.03", NamedTextColor.AQUA))
                .append(button("  ↑", "/rpgmaker adjust " + y + " 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust " + y + " -0.03", NamedTextColor.GREEN))
                .append(button("  ＋", "/rpgmaker adjust " + scale + " 0.02", NamedTextColor.GOLD))
                .append(button(" －", "/rpgmaker adjust " + scale + " -0.02", NamedTextColor.GOLD)));
    }

    private Component button(String text, String command, NamedTextColor color) {
        return Component.text(text, color).clickEvent(ClickEvent.runCommand(command));
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();
        if (skriptSyncReady && now % 20 == 0) Bukkit.getOnlinePlayers().forEach(this::syncRpgVariables);
        active.values().removeIf(dialogue -> {
            if (!dialogue.player.isOnline() || dialogue.expiresAt <= now) {
                dialogue.remove();
                return true;
            }
            if (dialogue.player.getInventory().getHeldItemSlot() != 8)
                dialogue.player.getInventory().setHeldItemSlot(8);
            position(dialogue);
            if (dialogue.editing) return false;
            if (dialogue.waitingForChat) {
                if (now % 20 == 0) dialogue.player.sendActionBar(Component.text("채팅에 값을 입력해 주세요.", NamedTextColor.YELLOW));
                return false;
            }
            if (dialogue.waitingForNext) {
                if (now % 20 == 0) dialogue.player.sendActionBar(Component.text("Shift 키를 눌러 다음 대사", NamedTextColor.GRAY));
                return false;
            }
            if (dialogue.waitingForClose) {
                if (now % 20 == 0) dialogue.player.sendActionBar(Component.text("Shift 키를 눌러 대화 종료", NamedTextColor.YELLOW));
                return false;
            }
            if (dialogue.typed < dialogue.message.length() && now >= dialogue.nextLetterAt) {
                dialogue.typed = skipWordColorMarker(dialogue.message, dialogue.typed);
                if (dialogue.typed >= dialogue.message.length()) {
                    render(dialogue);
                    finishPage(dialogue);
                    return false;
                }
                int codePoint = dialogue.message.codePointAt(dialogue.typed);
                dialogue.typed += Character.charCount(codePoint);
                dialogue.nextLetterAt = now + getConfig().getInt("typing-interval-ticks", 2);
                render(dialogue);
                if (!Character.isWhitespace(codePoint)) {
                    dialogue.player.playSound(dialogue.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT,
                            0.25f, 1.7f + (dialogue.typed % 3) * 0.08f);
                }
                if (dialogue.typed >= dialogue.message.length()) finishPage(dialogue);
            }
            return false;
        });
    }

    private int skipWordColorMarker(String text, int offset) {
        TextWidthRules.FormatToken inline = TextWidthRules.inlineFormat(text, offset);
        if (inline != null) return inline.end();
        if (offset > 0 && !Character.isWhitespace(text.codePointBefore(offset))) return offset;
        TextWidthRules.FormatToken word = TextWidthRules.wordFormat(text, offset);
        return word == null ? offset : word.end();
    }

    private void finishPage(Dialogue dialogue) {
        if (dialogue.message.isBlank()) {
            advanceFromPage(dialogue, true);
            return;
        }
        dialogue.choices = dialogue.pageIndex < dialogue.pageChoices.size()
                ? dialogue.pageChoices.get(dialogue.pageIndex).stream()
                        .filter(choice -> matchesCondition(dialogue.player, choice.condition)).toList()
                : List.of();
        if (!dialogue.choices.isEmpty()) {
            showChoices(dialogue);
            return;
        }
        PageRoute route = currentRoute(dialogue);
        boolean routeApplies = matchesCondition(dialogue.player, route.condition);
        int next = resolvedNextPage(dialogue, route, routeApplies);
        boolean terminal = route.terminal && routeApplies || dialogue.forcedTerminalPage == dialogue.pageIndex;
        if (!terminal && next >= 0 && next < dialogue.pages.size()) {
            dialogue.waitingForNext = true;
            dialogue.player.sendActionBar(Component.text("Shift 키를 눌러 다음 대사", NamedTextColor.GRAY));
            return;
        }
        dialogue.waitingForClose = true;
        dialogue.expiresAt = Integer.MAX_VALUE;
        dialogue.player.sendActionBar(Component.text("Shift 키를 눌러 대화 종료", NamedTextColor.YELLOW));
    }

    private void advanceFromPage(Dialogue dialogue, boolean automatic) {
        if (automatic && ++dialogue.autoTransitions > 64) {
            Bukkit.getScheduler().runTask(this, () -> close(dialogue.player));
            return;
        }
        Effect effect = applyCurrentPageEffect(dialogue);
        if (dialogue.waitingForChat || handleReturn(dialogue, effect)) return;
        PageRoute route = currentRoute(dialogue);
        boolean routeApplies = matchesCondition(dialogue.player, route.condition);
        if (route.terminal && routeApplies || dialogue.forcedTerminalPage == dialogue.pageIndex) {
            if (automatic) Bukkit.getScheduler().runTask(this, () -> close(dialogue.player));
            else close(dialogue.player);
            return;
        }
        int next = resolvedNextPage(dialogue, route, routeApplies);
        if (next < 0 || next >= dialogue.pages.size()) {
            if (automatic) Bukkit.getScheduler().runTask(this, () -> close(dialogue.player));
            else close(dialogue.player);
            return;
        }
        dialogue.speakerOverride = "";
        dialogue.pageIndex = next;
        restartPage(dialogue, false);
    }

    private PageRoute currentRoute(Dialogue dialogue) {
        return dialogue.pageIndex < dialogue.pageRoutes.size() ? dialogue.pageRoutes.get(dialogue.pageIndex) : PageRoute.DEFAULT;
    }

    private int resolvedNextPage(Dialogue dialogue, PageRoute route, boolean routeApplies) {
        if (routeApplies && route.jumpTiming.equals("AFTER") && route.jumpTarget > 0) return route.jumpTarget - 1;
        return route.nextPage > 0 ? route.nextPage - 1 : dialogue.pageIndex + 1;
    }

    private void render(Dialogue dialogue) {
        String visible = dialogue.message.substring(0, Math.min(dialogue.typed, dialogue.message.length()));
        Component body = Component.empty();
        List<String> rows = bodyRows(visible);
        for (int row = 0; row < rows.size(); row++) {
            if (row > 0) body = body.append(Component.newline());
            String line = rows.get(row);
            Component padding = Component.text(TextWidthRules.padding(line, MAXIMUM_LINE_PIXELS))
                    .font(Key.key("dialog", "spacing"));
            body = body.append(coloredLine(line)).append(Component.text(" ")).append(padding);
        }
        dialogue.bodyLines[0].text(body);
    }

    static List<String> bodyRows(String visible) {
        String[] lines = visible.split("\\n", -1);
        java.util.ArrayList<String> rows = new java.util.ArrayList<>(MAXIMUM_LINES);
        for (int row = 0; row < MAXIMUM_LINES; row++) rows.add(row < lines.length ? lines[row] : "");
        return rows;
    }

    private void clearBody(Dialogue dialogue) {
        for (TextDisplay line : dialogue.bodyLines) line.text(Component.empty());
    }

    private Component coloredLine(String line) {
        return formattedText(line, NamedTextColor.WHITE);
    }

    private Component formattedText(String line, TextColor defaultColor) {
        Component result = Component.empty();
        StringBuilder plain = new StringBuilder();
        TextWidthRules.TextFormat activeFormat = null;
        int offset = 0;
        boolean wordStart = true;
        while (offset < line.length()) {
            TextWidthRules.FormatToken inline = TextWidthRules.inlineFormat(line, offset);
            if (inline != null) {
                if (!plain.isEmpty()) result = result.append(formattedSegment(plain.toString(), activeFormat, defaultColor));
                plain.setLength(0);
                activeFormat = inline.format();
                offset = inline.end();
                continue;
            }
            TextWidthRules.FormatToken word = wordStart ? TextWidthRules.wordFormat(line, offset) : null;
            if (word != null) {
                if (!plain.isEmpty()) result = result.append(formattedSegment(plain.toString(), activeFormat, defaultColor));
                plain.setLength(0);
                int end = word.end();
                while (end < line.length() && !Character.isWhitespace(line.codePointAt(end)))
                    end += Character.charCount(line.codePointAt(end));
                result = result.append(formattedSegment(line.substring(word.end(), end), word.format(), defaultColor));
                offset = end;
                wordStart = false;
                continue;
            }
            int codePoint = line.codePointAt(offset);
            plain.appendCodePoint(codePoint);
            wordStart = Character.isWhitespace(codePoint);
            offset += Character.charCount(codePoint);
        }
        return plain.isEmpty() ? result : result.append(formattedSegment(plain.toString(), activeFormat, defaultColor));
    }

    private Component formattedSegment(String text, TextWidthRules.TextFormat format, TextColor defaultColor) {
        TextColor color = format == null ? defaultColor : TextColor.fromHexString(format.color());
        Component result = Component.text(text, color == null ? defaultColor : color);
        if (format == null) return result;
        if (format.bold()) result = result.decorate(TextDecoration.BOLD);
        if (format.italic()) result = result.decorate(TextDecoration.ITALIC);
        if (format.strikethrough()) result = result.decorate(TextDecoration.STRIKETHROUGH);
        return result;
    }

    private List<SingleOptionDialogInput.OptionEntry> characterOptions(String selected) {
        return List.of(
                option("SENTINEL", "수호자 (성별 없음)", selected), option("WARRIOR", "전사", selected),
                option("KING", "왕", selected), option("MAGE", "마법사", selected),
                option("ARCHER", "궁수", selected), option("DEMON", "악마 (성별 없음)", selected),
                option("KNIGHT", "기사", selected), option("MAGE_CLASS", "마도사", selected),
                option("RANGER", "레인저", selected), option("CLERIC", "성직자", selected),
                option("ROGUE", "도적", selected), option("NOBLE", "귀족", selected),
                option("VILLAGER", "마을 주민", selected), option("BLACKSMITH", "대장장이", selected),
                option("INNKEEPER", "여관주인", selected), option("SLIME", "슬라임 (몬스터)", selected),
                option("GOBLIN", "고블린 (몬스터)", selected), option("ORC", "오크 (몬스터)", selected),
                option("DRAGONKIN", "용인 (몬스터)", selected));
    }

    private List<SingleOptionDialogInput.OptionEntry> genderOptions(String selected, String character) {
        return List.of(option("MALE", "남성", selected), option("FEMALE", "여성", selected));
    }

    private List<SingleOptionDialogInput.OptionEntry> expressionOptions(String selected, String portrait) {
        if (List.of("SLIME", "GOBLIN", "ORC", "DRAGONKIN").contains(portrait))
            return List.of(option("NEUTRAL", "무표정 (고정)", "NEUTRAL"));
        java.util.ArrayList<SingleOptionDialogInput.OptionEntry> options = new java.util.ArrayList<>(List.of(
                option("NEUTRAL", "무표정", selected), option("HAPPY", "기쁨", selected),
                option("SAD", "슬픔", selected), option("ANGRY", "화남", selected),
                option("SURPRISED", "당황", selected)));
        if (portrait.startsWith("FEMALE_") || List.of("MAGE", "ARCHER").contains(portrait))
            options.add(option("EMBARRASSED", "부끄러움", selected));
        return options;
    }

    private SingleOptionDialogInput.OptionEntry option(String value, String label, String selected) {
        return SingleOptionDialogInput.OptionEntry.create(value, Component.text(label), value.equals(selected));
    }

    private List<SingleOptionDialogInput.OptionEntry> pageNumberOptions(Player player, int selected, String zeroLabel) {
        java.util.ArrayList<SingleOptionDialogInput.OptionEntry> options = new java.util.ArrayList<>();
        options.add(option("0", zeroLabel, Integer.toString(selected)));
        List<String> pages = editorMessages(player);
        for (int page = 1; page <= pages.size(); page++)
            options.add(option(Integer.toString(page), page + " · " + previewText(pages.get(page - 1)), Integer.toString(selected)));
        return options;
    }

    private String characterFromPortrait(String portrait) {
        if (portrait == null) return "SENTINEL";
        if (portrait.endsWith("_WARRIOR")) return "WARRIOR";
        if (portrait.endsWith("_KING")) return "KING";
        if (portrait.endsWith("_WIZARD")) return "MAGE";
        if (portrait.endsWith("_ARCHER")) return "ARCHER";
        if (portrait.endsWith("_KNIGHT")) return "KNIGHT";
        if (portrait.endsWith("_MAGE")) return "MAGE_CLASS";
        if (portrait.endsWith("_RANGER")) return "RANGER";
        if (portrait.endsWith("_CLERIC")) return "CLERIC";
        if (portrait.endsWith("_ROGUE")) return "ROGUE";
        if (portrait.endsWith("_NOBLE")) return "NOBLE";
        if (portrait.endsWith("_VILLAGER")) return "VILLAGER";
        if (portrait.endsWith("_BLACKSMITH")) return "BLACKSMITH";
        if (portrait.endsWith("_INNKEEPER")) return "INNKEEPER";
        return portrait;
    }

    private String genderFromPortrait(String portrait) {
        return portrait != null && portrait.startsWith("FEMALE_") ? "FEMALE" : "MALE";
    }

    private String resolvePortrait(String character, String gender) {
        String sex = "FEMALE".equals(gender) ? "FEMALE" : "MALE";
        return switch (character == null ? "SENTINEL" : character) {
            case "KNIGHT", "RANGER", "CLERIC", "ROGUE", "NOBLE", "VILLAGER", "BLACKSMITH", "INNKEEPER" -> sex + "_" + character;
            case "MAGE_CLASS" -> sex + "_MAGE";
            case "WARRIOR" -> sex + "_WARRIOR";
            case "KING" -> sex + "_KING";
            case "MAGE" -> sex + "_WIZARD";
            case "ARCHER" -> sex + "_ARCHER";
            case "SENTINEL", "DEMON", "SLIME", "GOBLIN", "ORC", "DRAGONKIN" -> character;
            default -> "SENTINEL";
        };
    }

    private void updatePortrait(Dialogue dialogue) {
        boolean portraitVisible = portraitVisible(dialogue);
        boolean speakerVisible = speakerVisible(dialogue);
        dialogue.choiceFrame.text(speakerVisible ? smallDialogueFrame() : Component.empty());
        dialogue.speakerDisplay.text(speakerVisible
                ? formattedText(fixedSpeakerText(speakerForPage(dialogue)), NamedTextColor.GOLD) : Component.empty());
        if (!portraitVisible) {
            dialogue.portrait.text(Component.empty());
            return;
        }
        String portrait = dialogue.pageIndex < dialogue.pagePortraits.size() ? dialogue.pagePortraits.get(dialogue.pageIndex) : "SENTINEL";
        String expression = dialogue.pageIndex < dialogue.pageExpressions.size() ? dialogue.pageExpressions.get(dialogue.pageIndex) : "HAPPY";
        dialogue.portrait.text(Component.text(portraitGlyph(portrait, expression)).font(Key.key("dialog", "portrait")));
    }

    private boolean portraitVisible(Dialogue dialogue) {
        return portraitVisible(dialogue.showPortrait, dialogue.pagePortraitVisible, dialogue.pageIndex);
    }

    static boolean portraitVisible(boolean showPortrait, List<Boolean> pagePortraitVisible, int pageIndex) {
        return showPortrait && (pageIndex >= pagePortraitVisible.size() || pagePortraitVisible.get(pageIndex));
    }

    private boolean speakerVisible(Dialogue dialogue) {
        return speakerVisible(dialogue.showSpeaker, dialogue.pageSpeakerVisible, dialogue.pageIndex);
    }

    static boolean speakerVisible(boolean showSpeaker, List<Boolean> pageSpeakerVisible, int pageIndex) {
        return showSpeaker && (pageIndex >= pageSpeakerVisible.size() || pageSpeakerVisible.get(pageIndex));
    }

    private String speakerForPage(Dialogue dialogue) {
        if (!dialogue.speakerOverride.isBlank()) return dialogue.speakerOverride;
        if (dialogue.pageIndex < dialogue.pageRoutes.size() && !dialogue.pageRoutes.get(dialogue.pageIndex).speaker.isBlank())
            return dialogue.pageRoutes.get(dialogue.pageIndex).speaker;
        return dialogue.speaker;
    }

    private String portraitGlyph(String portrait, String expression) {
        int row = switch (portrait) {
            case "MALE_KNIGHT", "MALE_WARRIOR" -> 0; case "FEMALE_KNIGHT", "FEMALE_WARRIOR" -> 1;
            case "MALE_MAGE", "MALE_WIZARD" -> 2; case "FEMALE_MAGE", "FEMALE_WIZARD" -> 3;
            case "MALE_RANGER", "MALE_ARCHER" -> 4; case "FEMALE_RANGER", "FEMALE_ARCHER" -> 5;
            case "MALE_CLERIC" -> 6; case "FEMALE_CLERIC" -> 7;
            case "MALE_ROGUE" -> 8; case "FEMALE_ROGUE" -> 9;
            case "MALE_NOBLE", "MALE_KING" -> 10; case "FEMALE_NOBLE", "FEMALE_KING" -> 11;
            default -> -1;
        };
        if (row >= 0) {
            if (expression.equals("NEUTRAL") || portrait.startsWith("MALE_") && expression.equals("EMBARRASSED"))
                return Character.toString(0xE060 + row);
            int column = switch (expression) {
                case "SAD" -> 1; case "ANGRY" -> 2; case "SURPRISED" -> 3; case "EMBARRASSED" -> 4;
                default -> 0;
            };
            return Character.toString(row < 6 ? 0xE010 + row * 5 + column : 0xE030 + (row - 6) * 5 + column);
        }
        int villageRow = switch (portrait) {
            case "MALE_VILLAGER" -> 0; case "FEMALE_VILLAGER" -> 1;
            case "MALE_BLACKSMITH" -> 2; case "FEMALE_BLACKSMITH" -> 3;
            case "MALE_INNKEEPER" -> 4; case "FEMALE_INNKEEPER" -> 5;
            default -> -1;
        };
        if (villageRow >= 0) {
            int[] neutral = {0xE0A0, 0xE0A1, 0xE0A2, 0xE0A3, 0xE0A4, 0xE0A5};
            if (expression.equals("NEUTRAL") || portrait.startsWith("MALE_") && expression.equals("EMBARRASSED"))
                return Character.toString(neutral[villageRow]);
            int column = switch (expression) {
                case "SAD" -> 1; case "ANGRY" -> 2; case "SURPRISED" -> 3; case "EMBARRASSED" -> 4;
                default -> 0;
            };
            return Character.toString(0xE080 + villageRow * 5 + column);
        }
        return switch (portrait) {
            case "SLIME" -> "\uE06C";
            case "GOBLIN" -> "\uE06D";
            case "ORC" -> "\uE06E";
            case "DRAGONKIN" -> "\uE06F";
            case "WARRIOR" -> "\uE002";
            case "KING" -> "\uE003";
            case "MAGE" -> "\uE004";
            case "DEMON" -> "\uE005";
            case "ARCHER" -> "\uE006";
            default -> "\uE001";
        };
    }

    private String limitLine(String text) {
        return TextWidthRules.limitVisible(text, MAXIMUM_CHARACTERS_PER_LINE);
    }

    private String limitText(String text, int maximum) {
        if (text == null) return "";
        return text.codePointCount(0, text.length()) <= maximum ? text
                : text.substring(0, text.offsetByCodePoints(0, maximum));
    }

    private String fixedSpeakerText(String speaker) {
        return TextWidthRules.limitVisible(speaker, 10);
    }

    private Component dialogueFrame() {
        return Component.text("\uE000").font(Key.key("dialog", "frame"));
    }

    private Component smallDialogueFrame() {
        return Component.text("\uE000").font(Key.key("dialog", "choice_frame"));
    }

    private Component fixedChoiceLine(String text) {
        return Component.text(text, NamedTextColor.WHITE).append(Component.text(TextWidthRules.padding(text, CHOICE_LINE_PIXELS))
                .font(Key.key("dialog", "spacing")));
    }

    private Component editorChoicePreview() {
        return fixedChoiceLine("[1] 기록을 확인한다").append(Component.newline())
                .append(fixedChoiceLine("[2] 대화를 끝낸다")).append(Component.newline())
                .append(fixedChoiceLine("숫자키 1 또는 2로 선택"));
    }

    private void showChoices(Dialogue dialogue) {
        if (dialogue.choices.isEmpty()) return;
        Component lines = Component.empty();
        for (int i = 0; i < Math.min(8, dialogue.choices.size()); i++) {
            if (i > 0) lines = lines.append(Component.newline());
            lines = lines.append(fixedChoiceLine("[" + (i + 1) + "] "
                    + expandVariables(dialogue.player, dialogue.choices.get(i).label)));
        }
        lines = lines.append(Component.newline()).append(fixedChoiceLine(
                "숫자키 1~" + Math.min(8, dialogue.choices.size()) + "로 선택"));
        dialogue.choiceDisplay.text(lines);
        dialogue.waitingForChoice = true;
        dialogue.expiresAt = Integer.MAX_VALUE;
        dialogue.player.getInventory().setHeldItemSlot(8);
    }

    private void choose(Player player, String rawChoice) {
        Dialogue dialogue = active.get(player.getUniqueId());
        if (dialogue == null || !dialogue.waitingForChoice) return;
        int index;
        try { index = Integer.parseInt(rawChoice) - 1; }
        catch (NumberFormatException ignored) { return; }
        if (index < 0 || index >= dialogue.choices.size()) return;
        Effect effect = applyCurrentPageEffect(dialogue);
        if (dialogue.waitingForChat || handleReturn(dialogue, effect)) return;
        dialogue.history.push(snapshot(dialogue));
        Choice selected = dialogue.choices.get(index);
        if (selected.targetPage > 0 && selected.targetPage <= dialogue.pages.size()) {
            dialogue.speakerOverride = selected.speaker;
            dialogue.pageIndex = selected.targetPage - 1;
            dialogue.forcedTerminalPage = selected.endDialogue ? dialogue.pageIndex : -1;
            dialogue.appliedPages.remove(dialogue.pageIndex);
            restartPage(dialogue, false);
            dialogue.expiresAt = Integer.MAX_VALUE;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            return;
        }
        applyFlow(dialogue, continueFlow(flowState(dialogue), dialogue.pageIndex, selected), 0, false);
        dialogue.expiresAt = Integer.MAX_VALUE;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
    }

    private void applyEffect(Player player, Effect effect) {
        if (effect == null) return;
        boolean firstItem = true;
        for (String entry : effectEntries(effect.items)) {
            ItemSpec spec = parseItemSpec(player, entry);
            if (spec == null) continue;
            ItemStack item = itemStack(spec, firstItem ? effect.itemName : "", firstItem ? effect.itemColor : "#FFFFFF");
            firstItem = false;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            overflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        for (String entry : effectEntries(effect.takeItems)) {
            ItemSpec spec = parseItemSpec(player, entry);
            if (spec != null) removeItems(player, spec);
        }
        for (String entry : effectEntries(effect.variablesSet)) {
            java.util.regex.Matcher assignment = VARIABLE_ASSIGNMENT.matcher(entry);
            if (!assignment.matches()) continue;
            String variable = assignment.group(1).strip();
            String current = variable.isBlank() ? null : variableValue(player, variable);
            if (!variable.isBlank()) setVariableValue(player, variable, ExpressionRules.calculate(
                    current, assignment.group(2), expandVariables(player, assignment.group(3))));
        }
        for (String entry : effectEntries(effect.variablesDelete)) {
            String variable = entry.strip();
            if (!variable.isBlank()) deleteVariableValue(player, variable);
        }
        for (String entry : effectEntries(effect.sounds)) {
            SoundSpec spec = parseSoundSpec(entry);
            if (spec == null) continue;
            for (int repeat = 0; repeat < spec.repeats; repeat++) {
                Runnable play = () -> {
                    if (!player.isOnline()) return;
                    try { player.playSound(player.getLocation(), spec.sound, spec.volume, spec.pitch); }
                    catch (IllegalArgumentException ignored) { }
                };
                if (repeat == 0) play.run();
                else Bukkit.getScheduler().runTaskLater(this, play, repeat * 4L);
            }
        }
        if (!effect.message.isBlank()) {
            TextColor messageColor = TextColor.fromHexString(effect.messageColor);
            player.sendMessage(formattedText(expandVariables(player, effect.message),
                    messageColor == null ? NamedTextColor.WHITE : messageColor));
        }
        String command = effect.command == null ? "" : effect.command.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (!command.isBlank()) {
            String target = switch (effect.commandTarget) {
                case "ALL" -> "@a";
                case "NEAREST" -> "@p";
                default -> player.getName();
            };
            String resolved = command.replace("{target}", target).replace("{player}", player.getName());
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved))
                    player.sendMessage(Component.text("서버 명령어를 실행하지 못했습니다.", NamedTextColor.RED));
            } catch (org.bukkit.command.CommandException error) {
                Throwable cause = error.getCause();
                getLogger().warning("Invalid dialogue command ignored: " + resolved + " (" + (cause == null ? error.getMessage() : cause.getMessage()) + ")");
                player.sendMessage(Component.text("잘못된 서버 명령어를 건너뛰었습니다.", NamedTextColor.RED));
            }
        }
    }

    private List<String> effectEntries(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("[,\\n]")).map(String::strip).filter(entry -> !entry.isBlank()).toList();
    }

    private String inlineLegacyItem(String items, String name, String color) {
        if (items == null || items.isBlank() || name == null || name.isBlank()) return items == null ? "" : items;
        java.util.ArrayList<String> entries = new java.util.ArrayList<>(effectEntries(items));
        entries.set(0, entries.get(0) + ":" + name + ":" + (color == null || color.isBlank() ? "#FFFFFF" : color));
        return String.join(", ", entries);
    }

    private String customItemRoot(UUID owner) {
        return "custom-items." + owner;
    }

    private List<String> customItemNames(UUID owner) {
        var section = getConfig().getConfigurationSection(customItemRoot(owner));
        return section == null ? List.of() : section.getKeys(false).stream().filter(name -> !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<SingleOptionDialogInput.OptionEntry> customItemOptions(Player player, String selected) {
        UUID owner = editorOwner.getOrDefault(player.getUniqueId(), player.getUniqueId());
        java.util.ArrayList<SingleOptionDialogInput.OptionEntry> options = new java.util.ArrayList<>();
        options.add(option("NONE", "직접 입력 / 추가 안 함", selected == null || selected.isBlank() ? "NONE" : selected));
        boolean found = false;
        for (String name : customItemNames(owner)) {
            String value = "@" + owner + "/" + name;
            String title = getConfig().getString(customItemRoot(owner) + "." + name + ".title", name);
            options.add(option(value, title, selected));
            found |= value.equals(selected);
        }
        if (!found && selected != null && selected.startsWith("@"))
            options.add(option(selected, "현재 저장된 외부 아이템", selected));
        return options;
    }

    private String firstCustomReference(String value) {
        if (value == null) return "";
        return effectEntries(value).stream().filter(entry -> entry.startsWith("@"))
                .map(entry -> entry.substring(0, Math.max(0, entry.lastIndexOf(':')))).findFirst().orElse("");
    }

    private int customReferenceAmount(String value, String reference) {
        if (reference == null || reference.isBlank()) return 1;
        return effectEntries(value).stream().filter(entry -> entry.startsWith(reference + ":"))
                .map(entry -> entry.substring(entry.lastIndexOf(':') + 1)).mapToInt(this::safeItemAmount).findFirst().orElse(1);
    }

    private int safeItemAmount(String value) {
        try { return Math.max(1, Math.min(100, Integer.parseInt(value.strip()))); }
        catch (RuntimeException ignored) { return 1; }
    }

    private int safePageNumber(String value, int maximum) {
        try { return Math.max(0, Math.min(maximum, Integer.parseInt(value == null ? "0" : value.strip()))); }
        catch (RuntimeException ignored) { return 0; }
    }

    private String putCustomReference(String value, String reference, String amount) {
        java.util.ArrayList<String> entries = new java.util.ArrayList<>(effectEntries(value));
        entries.removeIf(entry -> entry.startsWith(reference + ":"));
        entries.add(reference + ":" + safeItemAmount(amount));
        return String.join(", ", entries);
    }

    private CustomItem loadCustomItem(String reference) {
        if (reference == null || !reference.startsWith("@") || !reference.contains("/")) return null;
        try {
            String[] parts = reference.substring(1).split("/", 2);
            UUID owner = UUID.fromString(parts[0]);
            String path = customItemRoot(owner) + "." + sanitizeName(parts[1]);
            ItemStack prototype = null;
            String encoded = getConfig().getString(path + ".item-bytes", "");
            if (!encoded.isBlank()) prototype = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            if (prototype == null) prototype = getConfig().getItemStack(path + ".item-stack");
            Material material = prototype == null ? Material.matchMaterial(getConfig().getString(path + ".material", "")) : prototype.getType();
            if (material == null || !getConfig().contains(path)) return null;
            return new CustomItem(reference, material, getConfig().getString(path + ".display-name", "특수 아이템"),
                    getConfig().getString(path + ".name-color", "#FFFFFF"),
                    getConfig().getStringList(path + ".lore-lines"), getConfig().getStringList(path + ".lore-colors"), prototype);
        } catch (IllegalArgumentException ignored) { return null; }
    }

    static String resolveItemOwner(String value, UUID owner) {
        return value == null ? null : value.replace("@OWNER/", "@" + owner + "/");
    }

    private ItemSpec parseItemSpec(Player player, String value) {
        if (value == null || value.isBlank()) return null;
        value = resolveItemOwner(value, player.getUniqueId());
        if (value.strip().startsWith("@")) {
            String entry = value.strip();
            int separator = entry.lastIndexOf(':');
            CustomItem custom = loadCustomItem(separator > 0 ? entry.substring(0, separator) : entry);
            if (custom == null) return null;
            return new ItemSpec(custom.material, separator > 0 ? safeItemAmount(entry.substring(separator + 1)) : 1, custom.name,
                    custom.color, custom.lore, custom.loreColors, custom.reference, custom.prototype);
        }
        Material direct = Material.matchMaterial(value.strip());
        if (direct != null) return new ItemSpec(direct, 1, "", "#FFFFFF", List.of(), List.of(), "", null);
        String[] parts = value.strip().split(":", -1);
        for (int amountIndex = 1; amountIndex < parts.length; amountIndex++) {
            Material material = Material.matchMaterial(String.join(":", java.util.Arrays.copyOf(parts, amountIndex)).strip());
            if (material == null) continue;
            try {
                int amount = Math.max(1, Math.min(100, Integer.parseInt(parts[amountIndex].strip())));
                String name = amountIndex + 1 < parts.length ? parts[amountIndex + 1].strip() : "";
                String color = amountIndex + 2 < parts.length ? parts[amountIndex + 2].strip() : "#FFFFFF";
                return new ItemSpec(material, amount, name, color, List.of(), List.of(), "", null);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private ItemStack itemStack(ItemSpec spec, String fallbackName, String fallbackColor) {
        ItemStack item = spec.prototype == null ? new ItemStack(spec.material, spec.amount) : spec.prototype.clone();
        item.setAmount(spec.amount);
        if (spec.prototype != null) {
            item.editMeta(meta -> meta.getPersistentDataContainer().set(
                    new NamespacedKey(this, "custom_item"), PersistentDataType.STRING, spec.customId));
            return item;
        }
        String name = spec.name.isBlank() ? fallbackName : spec.name;
        String colorCode = spec.name.isBlank() ? fallbackColor : spec.color;
        if (!name.isBlank()) {
            TextColor color = TextColor.fromHexString(colorCode);
            item.editMeta(meta -> meta.displayName(Component.text(name,
                    color == null ? NamedTextColor.WHITE : color).decoration(TextDecoration.ITALIC, false)));
        }
        if (!spec.lore.isEmpty() || !spec.customId.isBlank()) item.editMeta(meta -> {
            java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
            for (int line = 0; line < spec.lore.size(); line++) {
                TextColor color = TextColor.fromHexString(line < spec.loreColors.size() ? spec.loreColors.get(line) : "#AAAAAA");
                lore.add(Component.text(spec.lore.get(line), color == null ? NamedTextColor.GRAY : color)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            if (!spec.customId.isBlank()) meta.getPersistentDataContainer().set(
                    new NamespacedKey(this, "custom_item"), PersistentDataType.STRING, spec.customId);
        });
        return item;
    }

    private void removeItems(Player player, ItemSpec spec) {
        int remaining = spec.amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!matchesItem(item, spec)) continue;
            int removed = Math.min(remaining, item.getAmount());
            remaining -= removed;
            if (removed == item.getAmount()) player.getInventory().setItem(slot, null);
            else item.setAmount(item.getAmount() - removed);
        }
    }

    private boolean matchesItem(ItemStack item, ItemSpec spec) {
        if (item == null || item.getType() != spec.material) return false;
        if (spec.prototype != null) {
            ItemStack actual = item.clone();
            ItemStack expected = spec.prototype.clone();
            actual.setAmount(1);
            expected.setAmount(1);
            actual.editMeta(meta -> meta.getPersistentDataContainer().remove(new NamespacedKey(this, "custom_item")));
            expected.editMeta(meta -> meta.getPersistentDataContainer().remove(new NamespacedKey(this, "custom_item")));
            return actual.isSimilar(expected);
        }
        if (!spec.customId.isBlank()) return item.hasItemMeta() && spec.customId.equals(item.getItemMeta()
                .getPersistentDataContainer().get(new NamespacedKey(this, "custom_item"), PersistentDataType.STRING));
        if (spec.name.isBlank()) return true;
        return item.hasItemMeta() && item.getItemMeta().displayName() != null
                && PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).equals(spec.name);
    }

    private SoundSpec parseSoundSpec(String value) {
        String raw = value == null ? "" : value.strip();
        if (raw.isBlank()) return null;
        int namespace = raw.indexOf(':');
        String sound = raw;
        String[] options = new String[0];
        if (namespace >= 0) {
            String[] tail = raw.substring(namespace + 1).split(":", -1);
            sound = raw.substring(0, namespace + 1) + tail[0];
            options = java.util.Arrays.copyOfRange(tail, 1, tail.length);
        }
        if (options.length > 3) return null;
        try {
            float pitch = options.length < 1 || options[0].isBlank() ? 1.0f
                    : Math.max(0.5f, Math.min(2.0f, Float.parseFloat(options[0].strip())));
            float volume = options.length < 2 || options[1].isBlank() ? 1.0f
                    : Math.max(0.0f, Math.min(4.0f, Float.parseFloat(options[1].strip())));
            int repeats = options.length < 3 || options[2].isBlank() ? 1
                    : Math.max(1, Math.min(10, Integer.parseInt(options[2].strip())));
            return new SoundSpec(sound.toLowerCase(java.util.Locale.ROOT), pitch, volume, repeats);
        } catch (NumberFormatException ignored) { return null; }
    }

    static String variableName(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value.strip().toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFC)
                .replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    static String variableStoragePath(String variable) {
        if (variable.matches("[a-z0-9._-]+")) return "variable_" + variable;
        return "variable/u/" + java.util.HexFormat.of().formatHex(variable.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String variableFromStoragePath(String path) {
        if (path.startsWith("variable_")) return path.substring("variable_".length());
        if (!path.startsWith("variable/u/")) return "";
        try {
            return new String(java.util.HexFormat.of().parseHex(path.substring("variable/u/".length())), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) { return ""; }
    }

    static String variableDataKey(String variable) {
        return java.util.HexFormat.of().formatHex(variable.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String variableFromDataKey(String key) {
        try {
            return new String(java.util.HexFormat.of().parseHex(key), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) { return ""; }
    }

    private String variableDataPath(Player player, String variable) {
        return "player-variables." + player.getUniqueId() + "." + variableDataKey(variable);
    }

    private String variableValue(Player player, String rawVariable) {
        String exact = exactSkriptVariable(rawVariable);
        if (exact != null) return exactSkriptValue(player, exact);
        String variable = variableName(rawVariable);
        if (variable.startsWith("skript.")) return skriptValue(player, variable.substring("skript.".length()));
        var data = player.getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(this, variableStoragePath(variable));
        String stored = getConfig().getString(variableDataPath(player, variable), data.get(key, PersistentDataType.STRING));
        if (!skriptBridgeReady) return stored;
        String mirrored = skriptValue(player, variable);
        boolean initialized = data.getOrDefault(skriptSyncKey, PersistentDataType.BOOLEAN, false);
        String resolved = resolvedRpgVariable(stored, mirrored, initialized);
        if (resolved == null) data.remove(key);
        else if (!resolved.equals(data.get(key, PersistentDataType.STRING))) data.set(key, PersistentDataType.STRING, resolved);
        return resolved;
    }

    static String resolvedRpgVariable(String stored, String mirrored, boolean initialized) {
        return mirrored != null ? mirrored : initialized ? null : stored;
    }

    private void setVariableValue(Player player, String rawVariable, String value) {
        String exact = exactSkriptVariable(rawVariable);
        if (exact != null) {
            setExactSkriptValue(player, exact, value);
            return;
        }
        String variable = variableName(rawVariable);
        if (variable.startsWith("skript.")) {
            setSkriptValue(player, variable.substring("skript.".length()), value);
            return;
        }
        player.getPersistentDataContainer().set(new NamespacedKey(this, variableStoragePath(variable)), PersistentDataType.STRING, value);
        getConfig().set(variableDataPath(player, variable), value);
        saveConfig();
        setSkriptValue(player, variable, value);
    }

    private void deleteVariableValue(Player player, String rawVariable) {
        String exact = exactSkriptVariable(rawVariable);
        if (exact != null) {
            deleteExactSkriptValue(player, exact);
            return;
        }
        String variable = variableName(rawVariable);
        if (variable.startsWith("skript.")) {
            deleteSkriptValue(player, variable.substring("skript.".length()));
            return;
        }
        player.getPersistentDataContainer().remove(new NamespacedKey(this, variableStoragePath(variable)));
        getConfig().set(variableDataPath(player, variable), null);
        saveConfig();
        deleteSkriptValue(player, variable);
    }

    private String skriptKey(Player player, String rawName) {
        return "rpgmaker::" + player.getUniqueId() + "::" + variableName(rawName);
    }

    private String skriptValue(Player player, String rawName) {
        if (!skriptBridgeReady || rawName.isBlank()) return null;
        Object value = Variables.getVariable(skriptKey(player, rawName), null, false);
        return skriptText(value);
    }

    private void setSkriptValue(Player player, String rawName, String value) {
        if (skriptBridgeReady && !rawName.isBlank())
            Variables.setVariable(skriptKey(player, rawName), ExpressionRules.typedValue(value), null, false);
    }

    private void deleteSkriptValue(Player player, String rawName) {
        if (skriptBridgeReady && !rawName.isBlank())
            Variables.deleteVariable(skriptKey(player, rawName), null, false);
    }

    static String exactSkriptVariable(String rawVariable) {
        if (rawVariable == null) return null;
        String value = rawVariable.strip();
        if (value.regionMatches(true, 0, "skript:", 0, "skript:".length()))
            value = value.substring("skript:".length()).strip();
        else if (!(value.startsWith("{") && value.endsWith("}"))) return null;
        if (value.startsWith("{") && value.endsWith("}")) value = value.substring(1, value.length() - 1).strip();
        return value.isBlank() ? null : value;
    }

    private String exactSkriptValue(Player player, String rawName) {
        if (!skriptBridgeReady) return null;
        String name = resolveSkriptPlayerTokens(player, rawName);
        if (name.isBlank() || name.startsWith("_")) return null;
        return skriptText(Variables.getVariable(name, null, false));
    }

    private void setExactSkriptValue(Player player, String rawName, String value) {
        if (!skriptBridgeReady) return;
        String name = resolveSkriptPlayerTokens(player, rawName);
        if (!name.isBlank() && !name.startsWith("_") && !name.endsWith("::*"))
            Variables.setVariable(name, ExpressionRules.typedValue(value), null, false);
    }

    private void deleteExactSkriptValue(Player player, String rawName) {
        if (!skriptBridgeReady) return;
        String name = resolveSkriptPlayerTokens(player, rawName);
        if (!name.isBlank() && !name.startsWith("_")) Variables.deleteVariable(name, null, false);
    }

    private String expandSkriptExpressions(Player player, String text) {
        if (!skriptBridgeReady || text.indexOf('%') < 0) return text;
        return replaceSkriptVariablePlaceholders(resolveSkriptPlayerTokens(player, text), name ->
                name.startsWith("_") ? null : skriptText(Variables.getVariable(name, null, false)));
    }

    static String replaceSkriptVariablePlaceholders(String text, java.util.function.Function<String, String> resolver) {
        java.util.regex.Matcher matcher = SKRIPT_VARIABLE_PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1).strip();
            String value = resolver.apply(name);
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveSkriptPlayerTokens(Player player, String text) {
        return resolveSkriptPlayerTokens(text, player.getName(), player.getUniqueId(), Classes.toString(player.getLocation()));
    }

    static String resolveSkriptPlayerTokens(String text, String playerName, UUID playerId, String location) {
        return text.replace("%player's location%", location)
                .replace("%uuid of player%", playerId.toString())
                .replace("%player%", playerName);
    }

    private String skriptText(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> values)
            return values.values().stream().map(Classes::toString).collect(java.util.stream.Collectors.joining(", "));
        return Classes.toString(value);
    }

    private void migrateStoredSkriptVariables() {
        if (!skriptBridgeReady) return;
        boolean changed = false;
        var iterator = Variables.getVariableIterator("rpgmaker::*", false, null);
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String[] parts = storedSkriptVariableParts(entry.getFirst());
            String value = skriptText(entry.getSecond());
            if (parts == null || value == null) continue;
            String path = "player-variables." + parts[0] + "." + variableDataKey(parts[1]);
            if (!value.equals(getConfig().getString(path))) {
                getConfig().set(path, value);
                changed = true;
            }
        }
        if (changed) saveConfig();
    }

    static String[] storedSkriptVariableParts(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.startsWith("rpgmaker::")) value = value.substring("rpgmaker::".length());
        while (value.startsWith("::")) value = value.substring(2);
        int separator = value.indexOf("::");
        if (separator < 1) return null;
        String owner = value.substring(0, separator);
        String variable = variableName(value.substring(separator + 2));
        try {
            UUID.fromString(owner);
            return variable.isBlank() ? null : new String[]{owner, variable};
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private void syncRpgVariables(Player player) {
        if (!skriptBridgeReady) return;
        var data = player.getPersistentDataContainer();
        String namespace = getName().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> stored = new HashMap<>();
        var saved = getConfig().getConfigurationSection("player-variables." + player.getUniqueId());
        if (saved != null) saved.getKeys(false).forEach(key -> {
            String variable = variableFromDataKey(key);
            String value = saved.getString(key);
            if (!variable.isBlank() && value != null) stored.put(variable, value);
        });
        for (NamespacedKey key : data.getKeys()) {
            String variable = key.getNamespace().equals(namespace) ? variableFromStoragePath(key.getKey()) : "";
            String value = variable.isBlank() ? null : data.get(key, PersistentDataType.STRING);
            if (value != null) stored.put(variable, value);
        }
        if (!skriptSyncReady) {
            stored.forEach((variable, value) -> {
                data.set(new NamespacedKey(this, variableStoragePath(variable)), PersistentDataType.STRING, value);
                setSkriptValue(player, variable, value);
            });
            savePlayerVariables(player, stored);
            return;
        }

        Map<String, String> mirrored = new HashMap<>();
        var iterator = Variables.getVariableIterator("rpgmaker::" + player.getUniqueId() + "::*", false, null);
        while (iterator.hasNext()) {
            var entry = iterator.next();
            String variable = variableName(entry.getFirst());
            String value = skriptText(entry.getSecond());
            if (!variable.isBlank() && value != null) mirrored.put(variable, value);
        }
        boolean initialized = data.getOrDefault(skriptSyncKey, PersistentDataType.BOOLEAN, false);
        Map<String, String> resolved = synchronizedRpgVariables(stored, mirrored, initialized);
        stored.keySet().stream().filter(variable -> !resolved.containsKey(variable))
                .forEach(variable -> data.remove(new NamespacedKey(this, variableStoragePath(variable))));
        resolved.forEach((variable, value) -> {
            data.set(new NamespacedKey(this, variableStoragePath(variable)), PersistentDataType.STRING, value);
            if (!value.equals(mirrored.get(variable))) setSkriptValue(player, variable, value);
        });
        data.set(skriptSyncKey, PersistentDataType.BOOLEAN, true);
        savePlayerVariables(player, resolved);
    }

    private void savePlayerVariables(Player player, Map<String, String> variables) {
        String root = "player-variables." + player.getUniqueId();
        Map<String, String> current = new HashMap<>();
        var section = getConfig().getConfigurationSection(root);
        if (section != null) section.getKeys(false).forEach(key -> current.put(key, section.getString(key, "")));
        Map<String, String> encoded = new HashMap<>();
        variables.forEach((name, value) -> encoded.put(variableDataKey(name), value));
        if (current.equals(encoded)) return;
        getConfig().set(root, null);
        encoded.forEach((key, value) -> getConfig().set(root + "." + key, value));
        saveConfig();
    }

    static Map<String, String> synchronizedRpgVariables(Map<String, String> stored, Map<String, String> mirrored,
                                                         boolean initialized) {
        Map<String, String> resolved = new HashMap<>();
        if (!initialized) resolved.putAll(stored);
        resolved.putAll(mirrored);
        return resolved;
    }

    private String expandVariables(Player player, String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        text = expandSkriptExpressions(player, text);
        java.util.regex.Matcher matcher = VARIABLE_PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String variable = matcher.group(1).strip();
            String value = builtInVariable(player, variableName(variable));
            if (value == null) value = variableValue(player, variable);
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String expandDialogueText(Player player, String text) {
        if (text == null) return "";
        return String.join("\n", java.util.Arrays.stream(text.split("\\n", -1))
                .map(this::limitLine).map(line -> expandVariables(player, line)).toList());
    }

    private String builtInVariable(Player player, String variable) {
        Dialogue dialogue = active.get(player.getUniqueId());
        ItemStack item = dialogue == null ? player.getInventory().getItemInMainHand() : dialogue.originalMainHandItem;
        return switch (variable) {
            case "player_name" -> player.getName();
            case "player_uuid" -> player.getUniqueId().toString();
            case "player_world" -> player.getWorld().getName();
            case "player_x" -> Integer.toString(player.getLocation().getBlockX());
            case "player_y" -> Integer.toString(player.getLocation().getBlockY());
            case "player_z" -> Integer.toString(player.getLocation().getBlockZ());
            case "player_health" -> String.format(java.util.Locale.ROOT, "%.1f", player.getHealth());
            case "held_item_name" -> heldItemName(item);
            case "held_item_type" -> item == null || item.getType().isAir() ? "minecraft:air" : item.getType().getKey().asString();
            case "held_item_amount" -> Integer.toString(item == null || item.getType().isAir() ? 0 : item.getAmount());
            default -> null;
        };
    }

    private String heldItemName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "없음";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName())
            return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        return item.getType().getKey().asString();
    }

    private Effect applyCurrentPageEffect(Dialogue dialogue) {
        if (dialogue.appliedPages.add(dialogue.pageIndex) && dialogue.pageIndex < dialogue.pageEffects.size()) {
            Effect effect = dialogue.pageEffects.get(dialogue.pageIndex);
            applyEffect(dialogue.player, effect);
            requestChatInput(dialogue, effect.chatInputVariable);
            return effect;
        }
        return Effect.NONE;
    }

    private void requestChatInput(Dialogue dialogue, String rawVariable) {
        String variable = rawVariable == null ? "" : rawVariable.strip();
        if (variable.isBlank()) return;
        awaitingChatInputs.put(dialogue.player.getUniqueId(), variable);
        dialogue.waitingForChat = true;
        dialogue.player.sendMessage(Component.text("채팅에 값을 입력해 주세요.", NamedTextColor.GRAY));
        dialogue.player.sendActionBar(Component.text("채팅에 값을 입력해 주세요.", NamedTextColor.GRAY));
    }

    private boolean handleReturn(Dialogue dialogue, Effect effect) {
        if (effect.returnMode.equals("TARGET")) {
            ReturnTarget target = dialogue.returnTargets.get(effect.returnTarget);
            if (target == null) return false;
            applyFlow(dialogue, target.state, target.pageIndex, target.completed);
            return true;
        }
        if (effect.returnMode.equals("PREVIOUS_PAGE") && dialogue.pageIndex > 0) {
            dialogue.pageIndex--;
            if (skipHiddenPages(dialogue)) restartPage(dialogue, false);
            return true;
        }
        if (effect.returnMode.equals("PREVIOUS_CHOICE") && !dialogue.history.isEmpty()) {
            DialogueSnapshot state = dialogue.history.pop();
            dialogue.pages = state.pages;
            dialogue.pageChoices = state.pageChoices;
            dialogue.pageEffects = state.pageEffects;
            dialogue.pageConditions = state.pageConditions;
            dialogue.pagePortraits = state.pagePortraits;
            dialogue.pageExpressions = state.pageExpressions;
            dialogue.pagePortraitVisible = state.pagePortraitVisible;
            dialogue.pageSpeakerVisible = state.pageSpeakerVisible;
            dialogue.pageRoutes = state.pageRoutes;
            dialogue.pageIndex = state.pageIndex;
            dialogue.closeAfterPages = state.closeAfterPages;
            dialogue.appliedPages.clear();
            dialogue.appliedPages.addAll(state.appliedPages);
            restartPage(dialogue, true);
            return true;
        }
        return false;
    }

    private FlowState flowState(Dialogue dialogue) {
        return new FlowState(dialogue.pages, dialogue.pageChoices, dialogue.pageEffects, dialogue.pageConditions,
                dialogue.pagePortraits, dialogue.pageExpressions, dialogue.pagePortraitVisible,
                dialogue.pageSpeakerVisible, dialogue.pageRoutes, dialogue.closeAfterPages);
    }

    private FlowState continueFlow(FlowState state, int pageIndex, Choice selected) {
        List<String> responsePages = splitPages(selected.response);
        java.util.ArrayList<String> pages = new java.util.ArrayList<>(responsePages);
        java.util.ArrayList<List<Choice>> choices = new java.util.ArrayList<>();
        java.util.ArrayList<Effect> effects = new java.util.ArrayList<>();
        java.util.ArrayList<Condition> conditions = new java.util.ArrayList<>();
        java.util.ArrayList<String> portraits = new java.util.ArrayList<>();
        java.util.ArrayList<String> expressions = new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> portraitVisible = new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> speakerVisible = new java.util.ArrayList<>();
        java.util.ArrayList<PageRoute> routes = new java.util.ArrayList<>();
        String inheritedPortrait = pageIndex < state.pagePortraits.size() ? state.pagePortraits.get(pageIndex) : "SENTINEL";
        String inheritedExpression = pageIndex < state.pageExpressions.size() ? state.pageExpressions.get(pageIndex) : "HAPPY";
        String inheritedSpeaker = pageIndex < state.pageRoutes.size() ? state.pageRoutes.get(pageIndex).speaker : "";
        if (!selected.speaker.isBlank()) inheritedSpeaker = selected.speaker;
        for (int page = 0; page < responsePages.size(); page++) {
            choices.add(page < selected.responseChoices.size() ? selected.responseChoices.get(page) : List.of());
            effects.add(page < selected.responseEffects.size() ? selected.responseEffects.get(page) : Effect.NONE);
            conditions.add(Condition.NONE);
            String portrait = page < selected.responsePortraits.size() ? selected.responsePortraits.get(page) : "";
            String expression = page < selected.responseExpressions.size() ? selected.responseExpressions.get(page) : "";
            if (!portrait.isBlank()) inheritedPortrait = portrait;
            if (!expression.isBlank()) inheritedExpression = expression;
            portraits.add(inheritedPortrait);
            expressions.add(inheritedExpression);
            portraitVisible.add(page < selected.responsePortraitVisible.size() && selected.responsePortraitVisible.get(page));
            speakerVisible.add(page < selected.responseSpeakerVisible.size() && selected.responseSpeakerVisible.get(page));
            routes.add(new PageRoute(inheritedSpeaker, 0, false, 0, "AFTER", Condition.NONE));
        }
        boolean shouldEnd = state.closeAfterPages || selected.endDialogue;
        if (!shouldEnd) {
            for (int page = pageIndex + 1; page < state.pages.size(); page++) {
                pages.add(state.pages.get(page));
                choices.add(page < state.pageChoices.size() ? state.pageChoices.get(page) : List.of());
                effects.add(page < state.pageEffects.size() ? state.pageEffects.get(page) : Effect.NONE);
                conditions.add(page < state.pageConditions.size() ? state.pageConditions.get(page) : Condition.NONE);
                portraits.add(page < state.pagePortraits.size() ? state.pagePortraits.get(page) : "SENTINEL");
                expressions.add(page < state.pageExpressions.size() ? state.pageExpressions.get(page) : "HAPPY");
                portraitVisible.add(page < state.pagePortraitVisible.size() && state.pagePortraitVisible.get(page));
                speakerVisible.add(page < state.pageSpeakerVisible.size() && state.pageSpeakerVisible.get(page));
                routes.add(page < state.pageRoutes.size() ? state.pageRoutes.get(page) : PageRoute.DEFAULT);
            }
        }
        return new FlowState(pages, choices, effects, conditions, portraits, expressions, portraitVisible, speakerVisible, routes, shouldEnd);
    }

    private void applyFlow(Dialogue dialogue, FlowState state, int pageIndex, boolean completed) {
        dialogue.pages = state.pages;
        dialogue.pageChoices = state.pageChoices;
        dialogue.pageEffects = state.pageEffects;
        dialogue.pageConditions = state.pageConditions;
        dialogue.pagePortraits = state.pagePortraits;
        dialogue.pageExpressions = state.pageExpressions;
        dialogue.pagePortraitVisible = state.pagePortraitVisible;
        dialogue.pageSpeakerVisible = state.pageSpeakerVisible;
        dialogue.pageRoutes = state.pageRoutes;
        dialogue.closeAfterPages = state.closeAfterPages;
        dialogue.pageIndex = pageIndex;
        dialogue.forcedTerminalPage = -1;
        dialogue.appliedPages.clear();
        restartPage(dialogue, completed);
    }

    private void indexReturnTargets(Dialogue dialogue) {
        dialogue.returnTargets.clear();
        FlowState root = flowState(dialogue);
        for (int page = 0; page < root.pages.size(); page++) {
            String route = "p" + page;
            if (!root.pages.get(page).isBlank())
                dialogue.returnTargets.put("PAGE:" + route, new ReturnTarget(root, page, false));
            indexChoiceReturnTargets(dialogue, root, page, route);
        }
    }

    private void indexChoiceReturnTargets(Dialogue dialogue, FlowState state, int pageIndex, String route) {
        if (pageIndex >= state.pageChoices.size()) return;
        List<Choice> choices = state.pageChoices.get(pageIndex);
        for (int choiceIndex = 0; choiceIndex < choices.size(); choiceIndex++) {
            dialogue.returnTargets.put("CHOICE:" + route + "#c" + choiceIndex,
                    new ReturnTarget(state, pageIndex, true));
            Choice selected = choices.get(choiceIndex);
            FlowState child = continueFlow(state, pageIndex, selected);
            int responsePages = splitPages(selected.response).size();
            for (int page = 0; page < responsePages; page++) {
                String childRoute = route + "/c" + choiceIndex + "/p" + page;
                if (!child.pages.get(page).isBlank())
                    dialogue.returnTargets.put("PAGE:" + childRoute, new ReturnTarget(child, page, false));
                indexChoiceReturnTargets(dialogue, child, page, childRoute);
            }
        }
    }

    private DialogueSnapshot snapshot(Dialogue dialogue) {
        return new DialogueSnapshot(dialogue.pages, dialogue.pageChoices, dialogue.pageEffects, dialogue.pageConditions,
                dialogue.pagePortraits, dialogue.pageExpressions, dialogue.pagePortraitVisible, dialogue.pageSpeakerVisible, dialogue.pageRoutes,
                new HashSet<>(dialogue.appliedPages),
                dialogue.pageIndex, dialogue.closeAfterPages);
    }

    private void restartPage(Dialogue dialogue, boolean completed) {
        if (!skipHiddenPages(dialogue)) return;
        dialogue.typed = completed ? dialogue.message.length() : 0;
        if (!dialogue.message.isBlank()) dialogue.autoTransitions = 0;
        dialogue.nextLetterAt = Bukkit.getCurrentTick();
        dialogue.waitingForChoice = false;
        dialogue.waitingForNext = false;
        dialogue.waitingForClose = false;
        dialogue.choices = List.of();
        dialogue.choiceFrame.text(Component.empty());
        dialogue.choiceDisplay.text(Component.empty());
        clearBody(dialogue);
        dialogue.player.getInventory().setHeldItemSlot(8);
        dialogue.player.sendActionBar(Component.empty());
        updatePortrait(dialogue);
        applyScales(dialogue);
        if (completed || dialogue.message.isEmpty()) {
            dialogue.typed = dialogue.message.length();
            render(dialogue);
            finishPage(dialogue);
        }
    }

    private boolean skipHiddenPages(Dialogue dialogue) {
        int hops = 0;
        while (dialogue.pageIndex >= 0 && dialogue.pageIndex < dialogue.pages.size() && hops++ < 64) {
            PageRoute route = currentRoute(dialogue);
            if (route.jumpTiming.equals("BEFORE") && route.jumpTarget > 0
                    && matchesCondition(dialogue.player, route.condition) && route.jumpTarget - 1 != dialogue.pageIndex) {
                dialogue.pageIndex = route.jumpTarget - 1;
                dialogue.speakerOverride = "";
                continue;
            }
            Condition condition = dialogue.pageIndex < dialogue.pageConditions.size()
                    ? dialogue.pageConditions.get(dialogue.pageIndex) : Condition.NONE;
            dialogue.message = expandDialogueText(dialogue.player, ExpressionRules.conditionalText(
                    matchesCondition(dialogue.player, condition), dialogue.pages.get(dialogue.pageIndex), condition.replacement));
            return true;
        }
        Bukkit.getScheduler().runTask(this, () -> close(dialogue.player));
        return false;
    }

    private boolean matchesCondition(Player player, Condition condition) {
        if (condition == null || condition.type.equals("NONE")) return true;
        String variable = condition.variable.strip();
        String storedValue = variable.isBlank() ? null : variableValue(player, variable);
        boolean variableMatch = !variable.isBlank() && ExpressionRules.compare(
                storedValue, condition.operator, expandVariables(player, condition.value));
        java.util.ArrayList<Boolean> variableMatches = new java.util.ArrayList<>();
        if (!variable.isBlank()) variableMatches.add(variableMatch);
        for (String entry : condition.extraVariables.split(",")) {
            java.util.regex.Matcher check = VARIABLE_CHECK.matcher(entry.strip());
            if (!check.matches()) continue;
            String extra = check.group(1).strip();
            String actual = extra.isBlank() ? null : variableValue(player, extra);
            String operator = switch (check.group(2)) {
                case "!=" -> "NE"; case ">" -> "GT"; case ">=" -> "GTE";
                case "<" -> "LT"; case "<=" -> "LTE"; default -> "EQ";
            };
            variableMatches.add(ExpressionRules.compare(actual, operator, expandVariables(player, check.group(3))));
        }
        if (!variableMatches.isEmpty()) variableMatch = ExpressionRules.combine(variableMatches, condition.variableLogic);
        ItemSpec required = parseItemSpec(player, condition.itemSpec);
        int itemAmount = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (required != null && matchesItem(item, required)) itemAmount += item.getAmount();
        }
        boolean itemMatch = required != null && itemAmount >= required.amount;
        return switch (condition.type) {
            case "VARIABLE" -> variableMatch;
            case "ITEM" -> itemMatch;
            case "BOTH" -> variableMatch && itemMatch;
            case "ANY" -> variableMatch || itemMatch;
            default -> true;
        };
    }

    private List<String> splitPages(String text) {
        java.util.ArrayList<String> pages = new java.util.ArrayList<>(List.of(text.split("\\f", -1)));
        if (pages.isEmpty()) pages.add("");
        return pages;
    }

    private TextDisplay spawn(Player viewer, Location location, Component text) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.text(text);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setDefaultBackground(false);
            entity.setSeeThrough(false);
            entity.setTextOpacity((byte) 250);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setVisibleByDefault(false);
            entity.setViewRange(4.0f);
            entity.setInterpolationDuration(1);
            entity.setTeleportDuration(1);
        });
        viewer.showEntity(this, display);
        return display;
    }

    private void position(Dialogue d) {
        double uiScale = effectiveUiScale(d);
        Location eye = d.player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-8) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        } else {
            right.normalize();
        }
        Vector up = right.clone().crossProduct(forward).normalize();
        Location base = eye.clone().add(forward.clone().multiply(d.dialogueDistance))
                .add(right.clone().multiply(layout(d, "frame-x-offset", 0.0)))
                .add(up.clone().multiply(layout(d, "vertical-offset", -0.92)));
        face(base, forward); d.frame.teleport(base);
        if (d.showPortrait) {
            Location portraitAt = base.clone().add(right.clone().multiply(layout(d, "portrait-x-offset", -0.82) * uiScale))
                    .add(up.clone().multiply(layout(d, "portrait-vertical-offset", 0.01) * uiScale))
                    .subtract(forward.clone().multiply(0.080));
            face(portraitAt, forward); d.portrait.teleport(portraitAt);
        }
        if (d.showSpeaker) {
            Location speakerAt = base.clone().add(right.clone().multiply(layout(d, "speaker-x-offset", -1.10) * uiScale))
                    .add(up.clone().multiply(layout(d, "speaker-vertical-offset", 0.42) * uiScale))
                    .subtract(forward.clone().multiply(0.100));
            face(speakerAt, forward); d.speakerDisplay.teleport(speakerAt);
        }
        Location bodyAt = base.clone().add(right.clone().multiply(layout(d, "text-x-offset", -0.06) * uiScale))
                .add(up.clone().multiply((layout(d, "text-vertical-offset", 0.05)
                        + (MAXIMUM_LINES - 1) * 0.05) * uiScale))
                .subtract(forward.clone().multiply(0.100));
        face(bodyAt, forward);
        d.bodyLines[0].teleport(bodyAt);
        Location choiceFrameAt = base.clone().add(right.clone().multiply(layout(d, "choice-frame-x-offset",
                        layout(d, "choice-x-offset", -0.06)) * uiScale))
                .add(up.clone().multiply(layout(d, "choice-frame-vertical-offset", layout(d, "choice-vertical-offset", -0.20)) * uiScale))
                .subtract(forward.clone().multiply(0.010));
        face(choiceFrameAt, forward); d.choiceFrame.teleport(choiceFrameAt);
        Location choicesAt = base.clone().add(right.clone().multiply(layout(d, "choice-x-offset", -0.06) * uiScale))
                .add(up.clone().multiply(layout(d, "choice-vertical-offset", -0.20) * uiScale))
                .subtract(forward.clone().multiply(0.100));
        face(choicesAt, forward); d.choiceDisplay.teleport(choicesAt);
    }

    private void face(Location location, Vector forward) {
        location.setDirection(forward.clone().multiply(-1));
    }

    private Transformation scale(float value) {
        return new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(value, value, value), new AxisAngle4f());
    }

    private Transformation scale(float x, float y) {
        return new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(x, y, 1.0f), new AxisAngle4f());
    }

    private void close(Player player) {
        awaitingChatInputs.remove(player.getUniqueId());
        Dialogue old = active.remove(player.getUniqueId());
        if (old != null) old.remove();
        if (player.isOnline()) player.sendActionBar(Component.empty());
    }

    private void startPackServer() {
        String packName = getConfig().getString("pack-file", "RPGMaker-Pack.zip");
        Path pack = getServer().getWorldContainer().toPath().resolve(packName);
        if (!Files.isRegularFile(pack)) return;
        try {
            int port = getConfig().getInt("pack-server-port", 25566);
            packServer = HttpServer.create(new InetSocketAddress(port), 0);
            packServer.createContext("/" + packName, exchange -> {
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(405, -1); exchange.close(); return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, Files.size(pack));
                try (var out = exchange.getResponseBody()) { Files.copy(pack, out); }
            });
            packExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "dialogue-pack-server"); thread.setDaemon(true); return thread;
            });
            packServer.setExecutor(packExecutor); packServer.start();
            getLogger().info("Resource pack server listening on port " + port);
        } catch (IOException error) {
            getLogger().severe("Failed to start resource pack server: " + error.getMessage());
        }
    }

    private record Effect(String items, String takeItems, String itemName, String itemColor,
                          String variablesSet, String variablesDelete, String chatInputVariable, String sounds, String message, String messageColor,
                          String returnMode, String returnTarget,
                          String command, String commandTarget) {
        private static final Effect NONE = new Effect("", "", "", "#FFFFFF", "", "", "", "", "", "#FFFFFF", "NONE", "", "", "PLAYER");
    }

    private record CustomItem(String reference, Material material, String name, String color,
                              List<String> lore, List<String> loreColors, ItemStack prototype) {}

    private record ItemSpec(Material material, int amount, String name, String color,
                            List<String> lore, List<String> loreColors, String customId, ItemStack prototype) {}

    private record SoundSpec(String sound, float pitch, float volume, int repeats) {}

    private record Condition(String type, String variable, String value, String operator, String extraVariables, String variableLogic,
                             String itemSpec, String replacement) {
        private static final Condition NONE = new Condition("NONE", "", "", "EQ", "", "AND", "", "");
    }

    private record Choice(String label, String response, List<Effect> responseEffects,
                          List<String> responsePortraits, List<String> responseExpressions,
                          List<Boolean> responsePortraitVisible, List<Boolean> responseSpeakerVisible,
                          List<List<Choice>> responseChoices, boolean endDialogue, Condition condition,
                          int targetPage, String speaker) {}

    private record PageRoute(String speaker, int nextPage, boolean terminal, int jumpTarget,
                             String jumpTiming, Condition condition) {
        private static final PageRoute DEFAULT = new PageRoute("", 0, false, 0, "AFTER", Condition.NONE);
    }

    private record ChoiceContext(String path, int choice, int page) {}

    private record ReturnOption(String value, String label) {}

    private record FlowState(List<String> pages, List<List<Choice>> pageChoices, List<Effect> pageEffects,
                             List<Condition> pageConditions, List<String> pagePortraits,
                             List<String> pageExpressions, List<Boolean> pagePortraitVisible, List<Boolean> pageSpeakerVisible,
                             List<PageRoute> pageRoutes, boolean closeAfterPages) {}

    private record ReturnTarget(FlowState state, int pageIndex, boolean completed) {}

    private record DialogueSnapshot(List<String> pages, List<List<Choice>> pageChoices, List<Effect> pageEffects,
                                    List<Condition> pageConditions, List<String> pagePortraits,
                                    List<String> pageExpressions, List<Boolean> pagePortraitVisible, List<Boolean> pageSpeakerVisible,
                                    List<PageRoute> pageRoutes, Set<Integer> appliedPages,
                                    int pageIndex, boolean closeAfterPages) {}

    private final class Dialogue {
        final Player player;
        final TextDisplay frame, choiceFrame, portrait, speakerDisplay, choiceDisplay;
        final TextDisplay[] bodyLines;
        final boolean showPortrait, showSpeaker;
        final String speaker;
        final int originalHeldSlot;
        final ItemStack originalNinthItem;
        final ItemStack originalMainHandItem;
        final float lockedYaw, lockedPitch;
        final double dialogueDistance;
        String message;
        String speakerOverride = "";
        List<String> pages;
        List<List<Choice>> pageChoices = List.of();
        List<Effect> pageEffects = List.of();
        List<Condition> pageConditions = List.of();
        List<String> pagePortraits = List.of();
        List<String> pageExpressions = List.of();
        List<Boolean> pagePortraitVisible = List.of();
        List<Boolean> pageSpeakerVisible = List.of();
        List<PageRoute> pageRoutes = List.of();
        final Set<Integer> appliedPages = new HashSet<>();
        final Deque<DialogueSnapshot> history = new ArrayDeque<>();
        final Map<String, ReturnTarget> returnTargets = new HashMap<>();
        int pageIndex;
        List<Choice> choices;
        int typed;
        int nextLetterAt = Bukkit.getCurrentTick();
        int autoTransitions;
        int forcedTerminalPage = -1;
        int expiresAt = Bukkit.getCurrentTick() + 160;
        boolean waitingForChoice;
        boolean waitingForChat;
        boolean waitingForNext;
        boolean waitingForClose;
        boolean closeAfterPages;
        boolean finished;
        int finishCloseAt = Integer.MAX_VALUE;
        boolean editing;

        Dialogue(Player player, TextDisplay frame, TextDisplay choiceFrame, TextDisplay portrait, TextDisplay speakerDisplay, TextDisplay[] bodyLines,
                 TextDisplay choiceDisplay, boolean showPortrait, boolean showSpeaker,
                 String speaker, String message, List<Choice> choices,
                 float lockedYaw, float lockedPitch, double dialogueDistance,
                 int originalHeldSlot, ItemStack originalNinthItem, ItemStack originalMainHandItem) {
            this.player = player; this.frame = frame; this.choiceFrame = choiceFrame; this.portrait = portrait;
            this.speakerDisplay = speakerDisplay; this.bodyLines = bodyLines; this.choiceDisplay = choiceDisplay;
            this.showPortrait = showPortrait; this.showSpeaker = showSpeaker;
            this.speaker = speaker; this.message = message; this.choices = choices;
            this.lockedYaw = lockedYaw; this.lockedPitch = lockedPitch; this.dialogueDistance = dialogueDistance;
            this.pages = List.of(message);
            this.originalHeldSlot = originalHeldSlot;
            this.originalNinthItem = originalNinthItem;
            this.originalMainHandItem = originalMainHandItem;
            this.expiresAt = Integer.MAX_VALUE;
        }

        void remove() {
            restoreDialogueHotbar(player, originalNinthItem, originalHeldSlot);
            if (player.isOnline()) player.sendActionBar(Component.empty());
            removeDisplays();
        }

        void removeDisplays() {
            for (TextDisplay display : new TextDisplay[]{frame, choiceFrame, portrait, speakerDisplay, choiceDisplay})
                if (display != null && display.isValid()) display.remove();
            for (TextDisplay display : bodyLines) if (display != null && display.isValid()) display.remove();
        }
    }
}
