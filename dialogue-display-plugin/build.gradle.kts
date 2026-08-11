plugins { java }

group = "kr.hyuni.dialogue"
version = "1.1.3"

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(files("../plugins/Skript-2.16.1.jar"))
    compileOnly(files("../plugins/Citizens-2.0.40-b3957.jar"))
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.test { useJUnitPlatform() }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
    from("../dialogue-resource-pack/rpgmaker-character-manifest.json") {
        into("")
    }
}

/*
 * DialogueDisplayPlugin is currently a large legacy source file. To keep runtime-only
 * changes isolated from unrelated editor/runtime code, compilation uses a generated copy
 * where dialogue controls and display rendering are rewritten. The task is strict: if an
 * expected source block changes, the build fails instead of silently producing stale code.
 */
val generatedRuntimeSources = layout.buildDirectory.dir("generated/sources/dialogue-runtime").get().asFile
val prepareDialogueRuntimeSources = tasks.register("prepareDialogueRuntimeSources") {
    inputs.dir("src/main/java")
    outputs.dir(generatedRuntimeSources)

    doLast {
        delete(generatedRuntimeSources)
        copy {
            from("src/main/java")
            into(generatedRuntimeSources)
        }

        val source = generatedRuntimeSources.resolve("kr/hyuni/dialogue/DialogueDisplayPlugin.java")
        var text = source.readText(Charsets.UTF_8).replace("\r\n", "\n")

        fun replaceRequired(old: String, replacement: String, label: String) {
            check(text.contains(old)) { "Dialogue runtime patch point not found: $label" }
            text = text.replace(old, replacement)
        }

        replaceRequired("import org.bukkit.event.player.PlayerInputEvent;\n", "", "PlayerInputEvent import")
        replaceRequired("import org.bukkit.event.player.PlayerToggleSneakEvent;\n", "", "PlayerToggleSneakEvent import")

        replaceRequired(
            """    @EventHandler public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (active.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
""",
            """    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Dialogue dialogue = active.get(event.getPlayer().getUniqueId());
        if (dialogue == null) return;
        event.setCancelled(true);
        if (dialogue.editing || dialogue.waitingForChoice || dialogue.waitingForChat) return;

        long now = System.currentTimeMillis();
        if (dialogue.typed < dialogue.message.length()) {
            dialogue.typed = dialogue.message.length();
            render(dialogue);
            finishPage(dialogue);
            dialogue.manualRevealAdvanceUnlockAtMillis = now + 500L;
            return;
        }

        if (now < dialogue.manualRevealAdvanceUnlockAtMillis) return;
        if (dialogue.waitingForNext || dialogue.waitingForClose) advanceFromPage(dialogue, false);
    }
""",
            "F-key handler",
        )

        replaceRequired(
            """
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Dialogue dialogue = active.get(event.getPlayer().getUniqueId());
        if (dialogue == null || dialogue.editing || dialogue.waitingForChoice || dialogue.waitingForChat) return;
        event.setCancelled(true);
        if (dialogue.typed < dialogue.message.length()) return;
        if (dialogue.waitingForNext || dialogue.waitingForClose) advanceFromPage(dialogue, false);
    }
""",
            "",
            "legacy Shift handler",
        )

        replaceRequired(
            """
    @EventHandler
    public void onInput(PlayerInputEvent event) {
        if (!event.getInput().isJump()) return;
        Dialogue dialogue = active.get(event.getPlayer().getUniqueId());
        if (dialogue == null || dialogue.editing || dialogue.typed >= dialogue.message.length()) return;
        dialogue.typed = dialogue.message.length();
        render(dialogue);
        finishPage(dialogue);
    }
""",
            "",
            "legacy Space handler",
        )

        replaceRequired(
            "        dialogue.typed = completed ? dialogue.message.length() : 0;\n        if (!dialogue.message.isBlank()) dialogue.autoTransitions = 0;",
            "        dialogue.typed = completed ? dialogue.message.length() : 0;\n        dialogue.manualRevealAdvanceUnlockAtMillis = 0L;\n        if (!dialogue.message.isBlank()) dialogue.autoTransitions = 0;",
            "manual reveal lock reset",
        )
        replaceRequired(
            "        int forcedTerminalPage = -1;\n        int expiresAt = Bukkit.getCurrentTick() + 160;\n        boolean waitingForChoice;",
            "        int forcedTerminalPage = -1;\n        int expiresAt = Bukkit.getCurrentTick() + 160;\n        long manualRevealAdvanceUnlockAtMillis;\n        boolean waitingForChoice;",
            "manual reveal lock state",
        )

        replaceRequired(
            """    @EventHandler(ignoreCancelled = true)
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
""",
            """    @EventHandler(ignoreCancelled = true)
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
            if (variable.startsWith("@layout:")) {
                String key = variable.substring("@layout:".length());
                Dialogue edited = active.get(player.getUniqueId());
                if (edited == null || !edited.editing || !editableLayoutKey(key)) return;
                try {
                    double newValue = Double.parseDouble(captured);
                    if (!Double.isFinite(newValue) || Math.abs(newValue) > 20.0) throw new NumberFormatException();
                    setLayoutValue(edited, key, newValue);
                    applyScales(edited);
                    saveConfig();
                    player.sendMessage(Component.text(key + " 수치를 " + layoutNumber(newValue) + "(으)로 변경했습니다.", NamedTextColor.GREEN));
                    editorControls(player);
                } catch (NumberFormatException error) {
                    awaitingChatInputs.put(player.getUniqueId(), variable);
                    player.sendMessage(Component.text("올바른 숫자를 입력해 주세요. 현재 수치: " + layoutNumber(layoutValue(edited, key)), NamedTextColor.RED));
                }
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
""",
            "numeric layout chat input",
        )

        replaceRequired(
            "List.of(\"edit\", \"edit2\", \"edit3\", \"edit4\", \"adjust\", \"save\", \"show\", \"npc\")",
            "List.of(\"edit\", \"edit2\", \"edit3\", \"edit4\", \"adjust\", \"setvalue\", \"save\", \"show\", \"npc\")",
            "numeric layout permission gate",
        )

        replaceRequired(
            """        if (args.length == 3 && args[0].equalsIgnoreCase("adjust")) {
""",
            """        if (args.length == 2 && args[0].equalsIgnoreCase("setvalue")) {
            Dialogue edited = active.get(player.getUniqueId());
            if (edited == null || !edited.editing) return true;
            String key = args[1];
            if (!editableLayoutKey(key)) return true;
            awaitingChatInputs.put(player.getUniqueId(), "@layout:" + key);
            player.sendMessage(Component.text("현재 수치: " + layoutNumber(layoutValue(edited, key)), NamedTextColor.AQUA));
            player.sendMessage(Component.text("채팅에 새 수치를 입력해 주세요.", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("adjust")) {
""",
            "numeric layout command",
        )

        replaceRequired(
            """    private void editorFrameRow(Player player) {
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
""",
            """    private void editorFrameRow(Player player) {
        player.sendMessage(Component.text("외곽선 위치  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust frame-x-offset -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust frame-x-offset 0.03", NamedTextColor.AQUA))
                .append(button(" ↑", "/rpgmaker adjust vertical-offset 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust vertical-offset -0.03", NamedTextColor.GREEN))
                .append(button(" [X 수치]", "/rpgmaker setvalue frame-x-offset", NamedTextColor.WHITE))
                .append(button(" [Y 수치]", "/rpgmaker setvalue vertical-offset", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("외곽선 크기  ", NamedTextColor.YELLOW)
                .append(button("[폭＋]", "/rpgmaker adjust frame-scale-x 0.02", NamedTextColor.GOLD))
                .append(button(" [폭－]", "/rpgmaker adjust frame-scale-x -0.02", NamedTextColor.GOLD))
                .append(button(" [높이＋]", "/rpgmaker adjust frame-scale-y 0.02", NamedTextColor.LIGHT_PURPLE))
                .append(button(" [높이－]", "/rpgmaker adjust frame-scale-y -0.02", NamedTextColor.LIGHT_PURPLE))
                .append(button(" [폭 수치]", "/rpgmaker setvalue frame-scale-x", NamedTextColor.WHITE))
                .append(button(" [높이 수치]", "/rpgmaker setvalue frame-scale-y", NamedTextColor.WHITE)));
    }
""",
            "frame current values and numeric buttons",
        )

        replaceRequired(
            """    private void editorChoiceFrameRows(Player player) {
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
""",
            """    private void editorChoiceFrameRows(Player player) {
        player.sendMessage(Component.text("소형 박스 위치  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust choice-frame-x-offset -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust choice-frame-x-offset 0.03", NamedTextColor.AQUA))
                .append(button(" ↑", "/rpgmaker adjust choice-frame-vertical-offset 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust choice-frame-vertical-offset -0.03", NamedTextColor.GREEN))
                .append(button(" [X 수치]", "/rpgmaker setvalue choice-frame-x-offset", NamedTextColor.WHITE))
                .append(button(" [Y 수치]", "/rpgmaker setvalue choice-frame-vertical-offset", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("소형 박스 크기  ", NamedTextColor.YELLOW)
                .append(button("[전체＋]", "/rpgmaker adjust choice-frame-scale 0.02", NamedTextColor.GREEN))
                .append(button(" [전체－]", "/rpgmaker adjust choice-frame-scale -0.02", NamedTextColor.GREEN))
                .append(button(" [폭＋]", "/rpgmaker adjust choice-frame-scale-x 0.02", NamedTextColor.GOLD))
                .append(button(" [폭－]", "/rpgmaker adjust choice-frame-scale-x -0.02", NamedTextColor.GOLD))
                .append(button(" [높이＋]", "/rpgmaker adjust choice-frame-scale-y 0.02", NamedTextColor.LIGHT_PURPLE))
                .append(button(" [높이－]", "/rpgmaker adjust choice-frame-scale-y -0.02", NamedTextColor.LIGHT_PURPLE))
                .append(button(" [전체 수치]", "/rpgmaker setvalue choice-frame-scale", NamedTextColor.WHITE))
                .append(button(" [폭 수치]", "/rpgmaker setvalue choice-frame-scale-x", NamedTextColor.WHITE))
                .append(button(" [높이 수치]", "/rpgmaker setvalue choice-frame-scale-y", NamedTextColor.WHITE)));
    }
""",
            "choice frame current values and numeric buttons",
        )

        replaceRequired(
            """    private void editorRow(Player player, String name, String x, String y, String scale) {
        player.sendMessage(Component.text(name + "  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust " + x + " -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust " + x + " 0.03", NamedTextColor.AQUA))
                .append(button("  ↑", "/rpgmaker adjust " + y + " 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust " + y + " -0.03", NamedTextColor.GREEN))
                .append(button("  ＋", "/rpgmaker adjust " + scale + " 0.02", NamedTextColor.GOLD))
                .append(button(" －", "/rpgmaker adjust " + scale + " -0.02", NamedTextColor.GOLD)));
    }

    private Component button(String text, String command, NamedTextColor color) {
""",
            """    private void editorRow(Player player, String name, String x, String y, String scale) {
        player.sendMessage(Component.text(name + "  ", NamedTextColor.YELLOW)
                .append(button("←", "/rpgmaker adjust " + x + " -0.03", NamedTextColor.AQUA))
                .append(button(" →", "/rpgmaker adjust " + x + " 0.03", NamedTextColor.AQUA))
                .append(button(" ↑", "/rpgmaker adjust " + y + " 0.03", NamedTextColor.GREEN))
                .append(button(" ↓", "/rpgmaker adjust " + y + " -0.03", NamedTextColor.GREEN))
                .append(button(" ＋", "/rpgmaker adjust " + scale + " 0.02", NamedTextColor.GOLD))
                .append(button(" －", "/rpgmaker adjust " + scale + " -0.02", NamedTextColor.GOLD))
                .append(button(" [X 수치]", "/rpgmaker setvalue " + x, NamedTextColor.WHITE))
                .append(button(" [Y 수치]", "/rpgmaker setvalue " + y, NamedTextColor.WHITE))
                .append(button(" [크기 수치]", "/rpgmaker setvalue " + scale, NamedTextColor.WHITE)));
    }

    private boolean editableLayoutKey(String key) {
        return List.of("vertical-offset", "frame-x-offset", "frame-scale", "frame-scale-x", "frame-scale-y",
                "portrait-x-offset", "portrait-vertical-offset", "portrait-scale", "text-x-offset",
                "text-vertical-offset", "text-scale", "speaker-x-offset", "speaker-vertical-offset",
                "speaker-scale", "choice-x-offset", "choice-vertical-offset", "choice-scale",
                "choice-frame-x-offset", "choice-frame-vertical-offset", "choice-frame-scale",
                "choice-frame-scale-x", "choice-frame-scale-y").contains(key);
    }

    private double layoutValue(Dialogue dialogue, String key) {
        if (key.equals("choice-frame-scale"))
            return (layoutValue(dialogue, "choice-frame-scale-x") + layoutValue(dialogue, "choice-frame-scale-y")) / 2.0;
        double fallback = switch (key) {
            case "vertical-offset" -> -0.92;
            case "frame-x-offset" -> 0.0;
            case "frame-scale" -> 0.22;
            case "frame-scale-x", "frame-scale-y" -> layout(dialogue, "frame-scale", 0.22);
            case "portrait-x-offset" -> -0.82;
            case "portrait-vertical-offset" -> 0.01;
            case "portrait-scale" -> 0.24;
            case "text-x-offset" -> -0.06;
            case "text-vertical-offset" -> 0.05;
            case "text-scale" -> DEFAULT_TEXT_SIZE / 100.0;
            case "speaker-x-offset" -> -1.10;
            case "speaker-vertical-offset" -> 0.42;
            case "speaker-scale" -> 0.68;
            case "choice-x-offset" -> -0.06;
            case "choice-vertical-offset" -> -0.20;
            case "choice-scale" -> 0.60;
            case "choice-frame-x-offset" -> layout(dialogue, "choice-x-offset", -0.06);
            case "choice-frame-vertical-offset" -> layout(dialogue, "choice-vertical-offset", -0.20);
            case "choice-frame-scale-x" -> choiceFrameScaleDefault(dialogue, "x");
            case "choice-frame-scale-y" -> choiceFrameScaleDefault(dialogue, "y");
            default -> 0.0;
        };
        return layout(dialogue, key, fallback);
    }

    private void setLayoutValue(Dialogue dialogue, String key, double value) {
        String prefix = layoutPrefix(dialogue.showPortrait, dialogue.showSpeaker);
        if (key.equals("choice-frame-scale")) {
            getConfig().set(prefix + "choice-frame-scale-x", value);
            getConfig().set(prefix + "choice-frame-scale-y", value);
        } else getConfig().set(prefix + key, value);
    }

    private String layoutNumber(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.3f", value);
        while (text.contains(".") && text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private Component button(String text, String command, NamedTextColor color) {
""",
            "numeric editor rows and helpers",
        )

        text = text
            .replace("입력이 저장되었습니다. Shift 키를 눌러 계속", "입력이 저장되었습니다. F키를 눌러 계속")
            .replace("대화 중 Space: 대화문 스킵 · Shift: 다음 대사", "대화 중 F: 타이핑 전체 표시 / 다음 대사 · 수동 전체 표시 후 0.5초간 다음 진행 방지")
            .replace("Shift 키를 눌러 다음 대사", "F키를 눌러 다음 대사")
            .replace("Shift 키를 눌러 대화 종료", "F키를 눌러 대화 종료")
            .replace("후속 대사 후 쉬프트로 종료", "후속 대사 후 F키로 종료")

        replaceRequired(
            "        sender.sendMessage(Component.text(\"RPG Maker 도움말\", NamedTextColor.GOLD));",
            "        sender.sendMessage(Component.text(\"RPG Maker 도움말\", NamedTextColor.GOLD));\n        sender.sendMessage(Component.text(\"대화 조작: F키로 타이핑 전체 표시 / 다음 진행 · 수동 전체 표시 후 0.5초간 진행 잠금\", NamedTextColor.AQUA));",
            "server help F-key guidance",
        )

        check(!text.contains("PlayerInputEvent")) { "Legacy Space dialogue handler remains in generated source." }
        check(!text.contains("PlayerToggleSneakEvent")) { "Legacy Shift dialogue handler remains in generated source." }
        check(!text.contains("Shift 키를 눌러")) { "Legacy Shift guidance remains in generated source." }
        check(!text.contains("대화 중 Space:")) { "Legacy Space guidance remains in generated source." }
        check(!text.contains("후속 대사 후 쉬프트로 종료")) { "Legacy Shift choice guidance remains in generated source." }
        check(text.contains("TextDisplay[] bodyLines = {spawn(player, origin, Component.empty())};")) { "Body must use one multiline TextDisplay." }
        check(text.contains("editorRow(player, \"본문\", \"text-x-offset\", \"text-vertical-offset\", \"text-scale\")")) { "Unified body editor controls are missing." }
        check(text.contains("for (int row = 0; row < MAXIMUM_LINES; row++)")) { "Multiline body must keep four stable rows." }
        check(text.contains("/rpgmaker setvalue ")) { "Numeric editor controls were not generated." }
        check(text.contains("현재 수치: ")) { "Numeric chat prompt was not generated." }
        check(!text.contains(" · X \" + layoutNumber")) { "Editor controls expose numeric values before a numeric button is clicked." }

        source.writeText(text, Charsets.UTF_8)
    }
}

sourceSets.named("main") {
    java.setSrcDirs(listOf(generatedRuntimeSources))
}

tasks.compileJava {
    dependsOn(prepareDialogueRuntimeSources)
}

val serverPluginDirectory = layout.projectDirectory.dir("../plugins")

tasks.register<Copy>("deployToServer") {
    group = "distribution"
    description = "Builds RPGMaker and replaces plugins/RPGMaker.jar."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(serverPluginDirectory)
    rename { "RPGMaker.jar" }
    doLast {
        logger.lifecycle("RPGMaker ${project.version} deployed to ${serverPluginDirectory.file("RPGMaker.jar").asFile}")
    }
}
