package kr.hyuni.dialogue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class DialogueWebApi implements Listener {
    private static final String API_ROOT = "/api/v1/";
    private static final String EDITOR_DIALOG_SUFFIX = " rpgmaker:editor";
    private static final String TRANSITION_COMMAND = "rpgmaker-transition ";

    private final JavaPlugin plugin;
    private final DialogueCompatibilityService compatibility;
    private final CharacterRegistry characters;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String token;
    private final Set<String> allowedOrigins;
    private final int maxRequestBytes;
    private final WebPlayerSessions sessions;
    private final Set<UUID> editorMenuBypass = ConcurrentHashMap.newKeySet();

    private DialogueWebApi(JavaPlugin plugin, DialogueCompatibilityService compatibility,
                           CharacterRegistry characters, HttpServer server,
                           ExecutorService executor, String token, Set<String> allowedOrigins,
                           int maxRequestBytes, WebPlayerSessions sessions) {
        this.plugin = plugin;
        this.compatibility = compatibility;
        this.characters = characters;
        this.server = server;
        this.executor = executor;
        this.token = token;
        this.allowedOrigins = allowedOrigins;
        this.maxRequestBytes = maxRequestBytes;
        this.sessions = sessions;
    }

    static DialogueWebApi start(JavaPlugin plugin, DialogueCompatibilityService compatibility,
                                CharacterRegistry characters) {
        if (!plugin.getConfig().getBoolean("web-api.enabled", true)) {
            plugin.getLogger().info("RPGMaker Web API is disabled.");
            return null;
        }
        try {
            String bind = plugin.getConfig().getString("web-api.bind", "127.0.0.1");
            int port = plugin.getConfig().getInt("web-api.port", 25567);
            String token = plugin.getConfig().getString("web-api.token", "dev-local-token-change-me");
            List<String> configuredOrigins = plugin.getConfig().getStringList("web-api.allowed-origins");
            Set<String> origins = configuredOrigins.isEmpty()
                    ? Set.of("http://localhost:5173", "http://127.0.0.1:5173")
                    : Set.copyOf(configuredOrigins);
            int maxBytes = Math.max(16_384, plugin.getConfig().getInt("web-api.max-request-bytes", 1_048_576));
            WebPlayerSessions sessions = new WebPlayerSessions(plugin);

            HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "rpgmaker-web-api");
                thread.setDaemon(true);
                return thread;
            });
            DialogueWebApi api = new DialogueWebApi(plugin, compatibility, characters, server, executor,
                    token, origins, maxBytes, sessions);
            server.createContext(API_ROOT, api::handle);
            server.setExecutor(executor);
            server.start();
            Bukkit.getPluginManager().registerEvents(api, plugin);
            api.installCommandCompletion();
            plugin.getLogger().info("RPGMaker Web API listening on http://" + bind + ":" + port + API_ROOT);
            if (token.equals("dev-local-token-change-me"))
                plugin.getLogger().warning("RPGMaker Web API uses the development token. Change web-api.token before exposing the port.");
            return api;
        } catch (IOException error) {
            plugin.getLogger().severe("Failed to start RPGMaker Web API: " + error.getMessage());
            return null;
        }
    }

    private void installCommandCompletion() {
        var command = plugin.getCommand("rpgmaker");
        if (command == null) return;
        command.setTabCompleter((sender, currentCommand, alias, args) -> {
            List<String> inherited = plugin.onTabComplete(sender, currentCommand, alias, args);
            ArrayList<String> result = new ArrayList<>(inherited == null ? List.of() : inherited);
            if (args.length == 1 && "web".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))
                    && result.stream().noneMatch(value -> value.equalsIgnoreCase("web"))) result.add("web");
            result.sort(String.CASE_INSENSITIVE_ORDER);
            return result;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand().strip();
        if (command.startsWith(TRANSITION_COMMAND)) {
            event.setCancelled(true);
            executeTransition(command.substring(TRANSITION_COMMAND.length()));
            return;
        }
        if (!command.startsWith("dialog show ") || !command.endsWith(EDITOR_DIALOG_SUFFIX)) return;
        String playerName = command.substring("dialog show ".length(), command.length() - EDITOR_DIALOG_SUFFIX.length()).strip();
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) return;
        if (editorMenuBypass.remove(player.getUniqueId())) return;
        event.setCancelled(true);
        showEditorLauncher(player);
    }

    private void executeTransition(String arguments) {
        String[] parts = arguments.split(" ", 4);
        if (parts.length < 4) return;
        Player player = Bukkit.getPlayerExact(parts[0]);
        if (player == null) return;
        String targetDialogue = decode(parts[1]);
        String originalCommand = "-".equals(parts[2]) ? "" : decode(parts[2]);
        String commandTarget = parts[3].toUpperCase(java.util.Locale.ROOT);
        if (!originalCommand.isBlank()) {
            String target = switch (commandTarget) {
                case "ALL" -> "@a";
                case "NEAREST" -> "@p";
                default -> player.getName();
            };
            String resolved = originalCommand.replace("{player}", player.getName()).replace("{target}", target).strip();
            if (resolved.startsWith("/")) resolved = resolved.substring(1);
            if (!resolved.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
        if (targetDialogue.isBlank()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) Bukkit.dispatchCommand(player, "rpgmaker play " + targetDialogue);
        });
    }

    @EventHandler
    public void onEditorLauncherClick(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().equals(Key.key("rpgmakerweb", "open_editor"))) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        editorMenuBypass.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "dialog show " + player.getName() + " rpgmaker:editor"));
    }

    private void showEditorLauncher(Player player) {
        ActionButton web = ActionButton.builder(Component.text("웹 열기", NamedTextColor.AQUA)).width(200)
                .tooltip(Component.text("이 플레이어 계정으로 RPGMaker 웹 편집기를 엽니다."))
                .action(DialogAction.staticAction(ClickEvent.openUrl(issuePlayerLink(player))))
                .build();
        ActionButton editor = ActionButton.builder(Component.text("게임 내 편집기", NamedTextColor.GOLD)).width(200)
                .tooltip(Component.text("기존 G키 RPGMaker 편집 메뉴를 엽니다."))
                .action(DialogAction.customClick(Key.key("rpgmakerweb", "open_editor"), BinaryTagHolder.binaryTagHolder("{}")))
                .build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("RPGMaker", NamedTextColor.GOLD))
                        .pause(false).canCloseWithEscape(true).build())
                .type(DialogType.multiAction(List.of(web, editor)).columns(2).build()));
        player.showDialog(dialog);
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    String issuePlayerLink(Player player) {
        return sessions.issue(player);
    }

    private void handle(HttpExchange exchange) {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin)) {
                send(exchange, 403, Map.of("error", "origin_not_allowed"));
                return;
            }
            addCors(exchange, origin);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(API_ROOT)) {
                send(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            List<String> segments = pathSegments(path);
            String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
            if (segments.size() == 4 && segments.get(2).equals("session")
                    && segments.get(3).equals("auto") && method.equals("POST")) {
                InetAddress address = clientAddress(exchange.getRemoteAddress().getAddress(), forwarded);
                WebPlayerSessions.IssuedSession issued = sync(() -> sessions.issueByAddress(address));
                if (issued == null) {
                    send(exchange, 404, Map.of("error", "online_player_not_found_for_address"));
                } else {
                    WebPlayerSessions.Session session = issued.session();
                    send(exchange, 200, Map.of(
                            "sessionId", issued.id(),
                            "playerName", session.playerName(),
                            "ownerUuid", session.ownerUuid().toString(),
                            "expiresAt", session.expiresAt(),
                            "admin", session.admin()));
                }
                return;
            }

            WebPlayerSessions.Session requestSession = sessions.resolve(exchange.getRequestHeaders().getFirst("X-RPGMaker-Session"));
            if (!authenticated(exchange) && requestSession == null) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            WebPlayerSessions.Session playerSession = requestSession;

            if (segments.size() == 3 && segments.get(2).equals("me") && method.equals("GET")) {
                if (playerSession == null) {
                    send(exchange, 403, Map.of("error", "player_session_required"));
                    return;
                }
                send(exchange, 200, Map.of(
                        "connected", true,
                        "playerName", playerSession.playerName(),
                        "ownerUuid", playerSession.ownerUuid().toString(),
                        "expiresAt", playerSession.expiresAt(),
                        "admin", playerSession.admin()));
                return;
            }

            if (segments.size() == 4 && segments.get(2).equals("admin")
                    && segments.get(3).equals("owners") && method.equals("GET")) {
                if (playerSession == null || !playerSession.admin()) {
                    send(exchange, 403, Map.of("error", "admin_required"));
                    return;
                }
                send(exchange, 200, Map.of("owners", sync(compatibility::listOwners)));
                return;
            }

            if (segments.size() == 3 && segments.get(2).equals("status") && method.equals("GET")) {
                send(exchange, 200, Map.of(
                        "connected", true,
                        "apiVersion", 1,
                        "pluginVersion", plugin.getPluginMeta().getVersion(),
                        "characterManifestVersion", characters == null ? 0 : characters.schemaVersion()
                ));
                return;
            }
            if (segments.size() == 3 && segments.get(2).equals("characters") && method.equals("GET")) {
                if (characters == null) send(exchange, 503, Map.of("error", "character_manifest_unavailable"));
                else sendRaw(exchange, 200, characters.rawJson());
                return;
            }
            if (segments.size() == 3 && segments.get(2).equals("assets") && method.equals("GET")) {
                send(exchange, 200, Map.of(
                        "packFile", plugin.getConfig().getString("pack-file", "RPGMaker-Pack.zip"),
                        "characterManifest", "/api/v1/characters",
                        "manifestVersion", characters == null ? 0 : characters.schemaVersion()
                ));
                return;
            }
            if (segments.size() == 3 && segments.get(2).equals("validate") && method.equals("POST")) {
                Map<String, Object> body = readBody(exchange);
                Map<String, Object> dialogue = object(body.get("dialogue"));
                if (playerSession != null && !playerSession.admin() && containsServerCommand(dialogue)) {
                    send(exchange, 403, Map.of("error", "op_command_requires_admin"));
                    return;
                }
                List<String> issues = sync(() -> compatibility.validate(dialogue));
                send(exchange, issues.isEmpty() ? 200 : 422, Map.of("valid", issues.isEmpty(), "issues", issues));
                return;
            }

            if (segments.size() == 4 && segments.get(2).equals("items") && method.equals("GET")) {
                UUID owner;
                try {
                    owner = UUID.fromString(decode(segments.get(3)));
                } catch (IllegalArgumentException error) {
                    send(exchange, 400, Map.of("error", "invalid_owner_uuid"));
                    return;
                }
                if (!canAccessOwner(playerSession, owner)) {
                    send(exchange, 403, Map.of("error", "session_owner_mismatch"));
                    return;
                }
                send(exchange, 200, Map.of("items", sync(() -> compatibility.listItems(owner))));
                return;
            }

            if (segments.size() == 3 && segments.get(2).equals("public-dialogues") && method.equals("GET")) {
                send(exchange, 200, Map.of("dialogues", sync(compatibility::listPublic)));
                return;
            }

            if (segments.size() == 4 && segments.get(2).equals("public-dialogues") && method.equals("GET")) {
                String name = decode(segments.get(3));
                DialogueCompatibilityService.DialogueDocument document = sync(() -> compatibility.getPublic(name));
                if (document == null) {
                    send(exchange, 404, Map.of("error", "dialogue_not_found"));
                } else {
                    Map<String, Object> result = new LinkedHashMap<>(documentMap(document));
                    UUID owner = sync(() -> compatibility.publicOwner(name));
                    result.put("ownerUuid", owner == null ? "" : owner.toString());
                    result.put("publisher", sync(() -> compatibility.publicPublisher(name)));
                    send(exchange, 200, result);
                }
                return;
            }

            if (segments.size() == 5 && segments.get(2).equals("public-dialogues") && method.equals("PUT")) {
                UUID owner;
                try {
                    owner = UUID.fromString(decode(segments.get(3)));
                } catch (IllegalArgumentException error) {
                    send(exchange, 400, Map.of("error", "invalid_owner_uuid"));
                    return;
                }
                if (!canAccessOwner(playerSession, owner)) {
                    send(exchange, 403, Map.of("error", "session_owner_mismatch"));
                    return;
                }
                String name = decode(segments.get(4));
                Map<String, Object> body = readBody(exchange);
                String expected = text(body.get("expectedRevision"));
                Map<String, Object> dialogue = object(body.get("dialogue"));
                if (playerSession != null && !playerSession.admin() && containsServerCommand(dialogue)) {
                    send(exchange, 403, Map.of("error", "op_command_requires_admin"));
                    return;
                }
                Map<String, Object> runtimeDialogue = prepareRuntimeTransitions(dialogue);
                String publisher = java.util.Optional.ofNullable(Bukkit.getOfflinePlayer(owner).getName()).orElse(owner.toString());
                PublicSaveResult outcome = sync(() -> {
                    UUID existingOwner = compatibility.publicOwner(name);
                    if (existingOwner != null && !existingOwner.equals(owner)) return new PublicSaveResult(true, null);
                    return new PublicSaveResult(false,
                            compatibility.savePublic(owner, publisher, name, expected, runtimeDialogue));
                });
                if (outcome.forbidden()) {
                    send(exchange, 403, Map.of("error", "public_dialogue_read_only"));
                    return;
                }
                DialogueCompatibilityService.SaveResult result = outcome.result();
                if (result.conflict()) {
                    send(exchange, 409, Map.of("error", "revision_conflict",
                            "serverRevision", result.revision() == null ? "" : result.revision()));
                } else if (!result.errors().isEmpty()) {
                    send(exchange, 422, Map.of("error", "validation_failed", "issues", result.errors()));
                } else {
                    send(exchange, 200, Map.of("saved", true, "revision", result.revision()));
                }
                return;
            }

            if (segments.size() >= 4 && segments.get(2).equals("dialogues")) {
                UUID owner;
                try {
                    owner = UUID.fromString(decode(segments.get(3)));
                } catch (IllegalArgumentException error) {
                    send(exchange, 400, Map.of("error", "invalid_owner_uuid"));
                    return;
                }
                if (!canAccessOwner(playerSession, owner)) {
                    send(exchange, 403, Map.of("error", "session_owner_mismatch"));
                    return;
                }

                if (segments.size() == 4 && method.equals("GET")) {
                    send(exchange, 200, Map.of("dialogues", sync(() -> compatibility.list(owner))));
                    return;
                }

                if (segments.size() >= 5) {
                    String name = decode(segments.get(4));
                    if (segments.size() == 5 && method.equals("GET")) {
                        DialogueCompatibilityService.DialogueDocument document = sync(() -> compatibility.get(owner, name));
                        if (document == null) send(exchange, 404, Map.of("error", "dialogue_not_found"));
                        else send(exchange, 200, documentMap(document));
                        return;
                    }
                    if (segments.size() == 5 && method.equals("PUT")) {
                        Map<String, Object> body = readBody(exchange);
                        String expected = text(body.get("expectedRevision"));
                        Map<String, Object> dialogue = object(body.get("dialogue"));
                        if (playerSession != null && !playerSession.admin() && containsServerCommand(dialogue)) {
                            send(exchange, 403, Map.of("error", "op_command_requires_admin"));
                            return;
                        }
                        Map<String, Object> runtimeDialogue = prepareRuntimeTransitions(dialogue);
                        DialogueCompatibilityService.SaveResult result =
                                sync(() -> compatibility.save(owner, name, expected, runtimeDialogue));
                        if (result.conflict()) {
                            send(exchange, 409, Map.of("error", "revision_conflict",
                                    "serverRevision", result.revision() == null ? "" : result.revision()));
                        } else if (!result.errors().isEmpty()) {
                            send(exchange, 422, Map.of("error", "validation_failed", "issues", result.errors()));
                        } else {
                            plugin.getLogger().info("Web API saved dialogue " + owner + "/" + name
                                    + " from " + exchange.getRemoteAddress());
                            send(exchange, 200, Map.of("saved", true, "revision", result.revision()));
                        }
                        return;
                    }
                    if (segments.size() == 5 && method.equals("DELETE")) {
                        String expected = exchange.getRequestHeaders().getFirst("If-Match");
                        DialogueCompatibilityService.DeleteResult result = sync(() -> compatibility.delete(owner, name, expected));
                        if (result.conflict()) {
                            send(exchange, 409, Map.of("error", "revision_conflict",
                                    "serverRevision", result.revision() == null ? "" : result.revision()));
                        } else if (!result.deleted()) {
                            send(exchange, 404, Map.of("error", "dialogue_not_found"));
                        } else {
                            plugin.getLogger().info("Web API deleted dialogue " + owner + "/" + name
                                    + " from " + exchange.getRemoteAddress());
                            send(exchange, 200, Map.of("deleted", true));
                        }
                        return;
                    }
                    if (segments.size() == 6 && segments.get(5).equals("reload") && method.equals("POST")) {
                        DialogueCompatibilityService.DialogueDocument document = sync(() -> compatibility.get(owner, name));
                        if (document == null) send(exchange, 404, Map.of("error", "dialogue_not_found"));
                        else send(exchange, 200, Map.of(
                                "reloaded", true,
                                "revision", document.revision(),
                                "note", "DialogueDisplayPlugin reads the updated configuration on the next play."
                        ));
                        return;
                    }
                }
            }

            send(exchange, 404, Map.of("error", "not_found"));
        } catch (RequestTooLarge ignored) {
            safeSend(exchange, 413, Map.of("error", "request_too_large"));
        } catch (Exception error) {
            plugin.getLogger().warning("Web API request failed: " + error.getMessage());
            safeSend(exchange, 500, Map.of("error", "internal_error"));
        }
    }

    private Map<String, Object> prepareRuntimeTransitions(Map<String, Object> source) {
        Map<String, Object> dialogue = deepCopyMap(source);
        String nextDialogue = text(dialogue.get("next-dialogue")).strip();
        List<?> pages = dialogue.get("message-pages") instanceof List<?> list ? list : List.of(dialogue.getOrDefault("message", ""));
        if (!nextDialogue.isBlank() && !pages.isEmpty()) {
            Map<String, Object> pageEffects = mutableSection(dialogue, "page-effects");
            injectTransition(pageEffects, Integer.toString(pages.size() - 1), nextDialogue);
        }
        Object pageChoices = dialogue.get("page-choices");
        if (pageChoices instanceof Map<?, ?> choicesByPage) {
            for (Object value : choicesByPage.values()) if (value instanceof Map<?, ?> choices) prepareChoiceTransitions(castMap(choices));
        }
        return dialogue;
    }

    private void prepareChoiceTransitions(Map<String, Object> choices) {
        int count = integer(choices.get("choice-count"));
        for (int slot = 1; slot <= Math.min(8, count); slot++) {
            String targetDialogue = text(choices.get("target-dialogue-" + slot)).strip();
            if (!targetDialogue.isBlank()) {
                choices.put("end-" + slot, true);
                List<?> responsePages = choices.get("response-pages-" + slot) instanceof List<?> list ? list : List.of();
                if (responsePages.isEmpty()) injectTransition(choices, "effect-" + slot, targetDialogue);
                else {
                    Map<String, Object> effects = mutableSection(choices, "response-effects-" + slot);
                    injectTransition(effects, Integer.toString(responsePages.size() - 1), targetDialogue);
                }
            }
            Object nestedValue = choices.get("response-page-choices-" + slot);
            if (nestedValue instanceof Map<?, ?> nestedPages) {
                for (Object nested : nestedPages.values()) if (nested instanceof Map<?, ?> nestedChoices)
                    prepareChoiceTransitions(castMap(nestedChoices));
            }
        }
    }

    private void injectTransition(Map<String, Object> parent, String key, String targetDialogue) {
        Map<String, Object> effect = parent.get(key) instanceof Map<?, ?> map ? castMap(map) : new LinkedHashMap<>();
        parent.put(key, effect);
        String originalCommand = text(effect.get("command")).strip();
        String originalTarget = text(effect.get("command-target")).strip();
        if (originalTarget.isBlank()) originalTarget = "PLAYER";
        effect.put("command", TRANSITION_COMMAND.stripTrailing() + " {player} "
                + encode(targetDialogue) + " " + (originalCommand.isBlank() ? "-" : encode(originalCommand)) + " "
                + originalTarget.toUpperCase(java.util.Locale.ROOT));
        effect.put("command-target", "PLAYER");
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
        return copy;
    }

    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(String.valueOf(key), deepCopyValue(child)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(this::deepCopyValue).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private Map<String, Object> mutableSection(Map<String, Object> parent, String key) {
        if (parent.get(key) instanceof Map<?, ?> map) return castMap(map);
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private boolean authenticated(HttpExchange exchange) {
        if (token == null || token.isBlank()) return false;
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String supplied = auth != null && auth.startsWith("Bearer ") ? auth.substring(7)
                : exchange.getRequestHeaders().getFirst("X-RPGMaker-Token");
        if (supplied == null) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    static InetAddress clientAddress(InetAddress remote, String forwarded) throws IOException {
        if (!remote.isLoopbackAddress() || forwarded == null || forwarded.isBlank()) return remote;
        String value = forwarded.substring(forwarded.lastIndexOf(',') + 1).strip();
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return InetAddress.getByName(value);
    }

    static boolean canAccessOwner(WebPlayerSessions.Session session, UUID owner) {
        return session == null || session.admin() || session.ownerUuid().equals(owner);
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException, RequestTooLarge {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) try {
            if (Long.parseLong(contentLength) > maxRequestBytes) throw new RequestTooLarge();
        } catch (NumberFormatException ignored) {}
        byte[] bytes = exchange.getRequestBody().readNBytes(maxRequestBytes + 1);
        if (bytes.length > maxRequestBytes) throw new RequestTooLarge();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isBlank()) return Map.of();
        return WebJson.parseObject(text);
    }

    private <T> T sync(Supplier<T> work) throws Exception {
        if (Bukkit.isPrimaryThread()) return work.get();
        return Bukkit.getScheduler().callSyncMethod(plugin, work::get).get(5, TimeUnit.SECONDS);
    }

    private Map<String, Object> documentMap(DialogueCompatibilityService.DialogueDocument document) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", document.name());
        result.put("revision", document.revision());
        result.put("dialogue", stripRuntimeTransitions(document.data()));
        return result;
    }

    private Map<String, Object> stripRuntimeTransitions(Map<String, Object> source) {
        Map<String, Object> copy = deepCopyMap(source);
        stripRuntimeValue(copy);
        return copy;
    }

    private void stripRuntimeValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = castMap(raw);
            Object command = map.get("command");
            if (command instanceof String text && text.startsWith(TRANSITION_COMMAND)) {
                String[] parts = text.substring(TRANSITION_COMMAND.length()).split(" ", 4);
                if (parts.length == 4) {
                    String original = "-".equals(parts[2]) ? "" : decode(parts[2]);
                    if (original.isBlank()) map.remove("command");
                    else map.put("command", original);
                    map.put("command-target", parts[3]);
                }
            }
            for (Object child : new ArrayList<>(map.values())) stripRuntimeValue(child);
        } else if (value instanceof List<?> list) for (Object child : list) stripRuntimeValue(child);
    }

    private List<String> pathSegments(String path) {
        ArrayList<String> result = new ArrayList<>();
        for (String segment : path.split("/")) if (!segment.isBlank()) result.add(segment);
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean containsServerCommand(Map<String, Object> dialogue) {
        return containsServerCommandValue(dialogue);
    }

    private boolean containsServerCommandValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).equals("command") && entry.getValue() != null
                        && !String.valueOf(entry.getValue()).isBlank()) return true;
                if (containsServerCommandValue(entry.getValue())) return true;
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) if (containsServerCommandValue(child)) return true;
        }
        return false;
    }

    private void addCors(HttpExchange exchange, String origin) {
        if (origin != null && allowedOrigins.contains(origin))
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Vary", "Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Authorization, X-RPGMaker-Token, X-RPGMaker-Session, Content-Type, If-Match");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        sendRaw(exchange, status, WebJson.stringify(body));
    }

    private void safeSend(HttpExchange exchange, int status, Object body) {
        try { send(exchange, status, body); }
        catch (IOException ignored) { exchange.close(); }
    }

    private void sendRaw(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) { response.write(bytes); }
    }

    private record PublicSaveResult(boolean forbidden, DialogueCompatibilityService.SaveResult result) {}

    private static final class RequestTooLarge extends Exception {}
}
