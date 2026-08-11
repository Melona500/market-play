package kr.hyuni.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueDisplayPluginTest {
    @Test
    void multilineBodyAlwaysKeepsFourOrderedRows() {
        assertEquals(List.of("첫째", "둘째", "", ""), DialogueDisplayPlugin.bodyRows("첫째\n둘째"));
        assertEquals(List.of("첫째", "둘째", "셋째", "넷째"),
                DialogueDisplayPlugin.bodyRows("첫째\n둘째\n셋째\n넷째\n다섯째"));
    }

    @Test
    void portraitVisibilityFollowsEditorModeAndCurrentPage() {
        assertFalse(DialogueDisplayPlugin.portraitVisible(false, List.of(), 0));
        assertFalse(DialogueDisplayPlugin.portraitVisible(true, List.of(false), 0));
        assertTrue(DialogueDisplayPlugin.portraitVisible(true, List.of(true), 0));
        assertTrue(DialogueDisplayPlugin.portraitVisible(true, List.of(), 0));
        assertFalse(DialogueDisplayPlugin.speakerVisible(false, List.of(), 0));
        assertFalse(DialogueDisplayPlugin.speakerVisible(true, List.of(false), 0));
        assertTrue(DialogueDisplayPlugin.speakerVisible(true, List.of(true), 0));
        assertEquals("", DialogueDisplayPlugin.layoutPrefix(true, true));
        assertEquals("plain-", DialogueDisplayPlugin.layoutPrefix(false, false));
        assertEquals("speaker-only-", DialogueDisplayPlugin.layoutPrefix(false, true));
        assertEquals("portrait-only-", DialogueDisplayPlugin.layoutPrefix(true, false));
    }

    @Test
    void variablesAndColorsDoNotUseVisibleCharacterLimit() {
        assertEquals(6, TextWidthRules.visibleCharacters("123{{long_variable}}{#00FF00}456"));
        assertEquals("123{{name}}456", TextWidthRules.limitVisible("123{{name}}4567", 6));
        assertEquals("123{{name}}", TextWidthRules.limitVisible("123{{name}}", 3));
        assertEquals(2, TextWidthRules.visibleCharacters("#FF0000:bold,italic,strikethrough:화자"));
        assertEquals("#FF0000:bold:화자", TextWidthRules.limitVisible("#FF0000:bold:화자이름", 2));
        assertEquals(6, TextWidthRules.visibleCharacters("123{{한글_변수}}456"));
    }

    @Test
    void displayConditionUsesOriginalWhenMatchedAndReplacementWhenNotMatched() {
        assertTrue(ExpressionRules.compare("10", "GTE", "10"));
        assertFalse(ExpressionRules.compare("10", "LT", "10"));
        assertTrue(ExpressionRules.compare(null, "IS_UNSET", ""));
        assertEquals("원래 대사", ExpressionRules.conditionalText(true, "원래 대사", "대체 대사"));
        assertEquals("대체 대사", ExpressionRules.conditionalText(false, "원래 대사", "대체 대사"));
        assertEquals("원래 대사", ExpressionRules.conditionalText(false, "원래 대사", ""));
    }

    @Test
    void koreanVariablesUseSafeStorageAndNumericValuesKeepTheirType() {
        String variable = DialogueDisplayPlugin.variableName("호감도 값");
        String storage = DialogueDisplayPlugin.variableStoragePath(variable);
        assertEquals("호감도_값", variable);
        assertEquals(variable, DialogueDisplayPlugin.variableFromStoragePath(storage));
        assertEquals(variable, DialogueDisplayPlugin.variableFromDataKey(DialogueDisplayPlugin.variableDataKey(variable)));
        assertTrue(ExpressionRules.typedValue("123") instanceof Long);
        assertTrue(ExpressionRules.typedValue("12.5") instanceof Double);
        assertEquals("안녕", ExpressionRules.typedValue("안녕"));
    }

    @Test
    void skriptReferencesKeepGlobalAndDynamicVariableNames() {
        assertEquals("global_flag", DialogueDisplayPlugin.exactSkriptVariable("skript:global_flag"));
        assertEquals("quest::%uuid of player%", DialogueDisplayPlugin.exactSkriptVariable("{quest::%uuid of player%}"));
        assertEquals("list::*", DialogueDisplayPlugin.exactSkriptVariable("skript:{list::*}"));
        assertEquals(null, DialogueDisplayPlugin.exactSkriptVariable("quest.progress"));
        assertEquals(6, TextWidthRules.visibleCharacters("123%{quest::%uuid of player%}%456"));
    }

    @Test
    void skriptPlayerTokensResolveWithoutAnActiveParser() {
        UUID playerId = UUID.fromString("12d0a2f0-6e70-4f53-88f7-9571b4c6bced");
        assertEquals("Melona500|world at 1, 2, 3|%{quest::12d0a2f0-6e70-4f53-88f7-9571b4c6bced}%",
                DialogueDisplayPlugin.resolveSkriptPlayerTokens(
                        "%player%|%player's location%|%{quest::%uuid of player%}%",
                        "Melona500", playerId, "world at 1, 2, 3"));
        assertEquals("global / player / first, second",
                DialogueDisplayPlugin.replaceSkriptVariablePlaceholders(
                        "%{global}% / %{quest::12d0a2f0-6e70-4f53-88f7-9571b4c6bced}% / %{list::*}%",
                        name -> switch (name) {
                            case "global" -> "global";
                            case "quest::12d0a2f0-6e70-4f53-88f7-9571b4c6bced" -> "player";
                            case "list::*" -> "first, second";
                            default -> null;
                        }));
    }

    @Test
    void skriptMirrorWinsAndSynchronizesCreatesAndDeletes() {
        assertEquals("저장값", DialogueDisplayPlugin.resolvedRpgVariable("저장값", null, false));
        assertEquals("스크립트값", DialogueDisplayPlugin.resolvedRpgVariable("저장값", "스크립트값", true));
        assertNull(DialogueDisplayPlugin.resolvedRpgVariable("저장값", null, true));
        assertEquals(Map.of("기존", "1", "공통", "스크립트", "신규", "2"),
                DialogueDisplayPlugin.synchronizedRpgVariables(
                        Map.of("기존", "1", "공통", "RPGMaker"),
                        Map.of("공통", "스크립트", "신규", "2"), false));
        assertEquals(Map.of("공통", "스크립트", "신규", "2"),
                DialogueDisplayPlugin.synchronizedRpgVariables(
                        Map.of("삭제됨", "1", "공통", "RPGMaker"),
                        Map.of("공통", "스크립트", "신규", "2"), true));
    }

    @Test
    void storedSkriptVariablesSplitIntoOwnerAndName() {
        String owner = "12d0a2f0-6e70-4f53-88f7-9571b4c6bced";
        assertEquals(List.of(owner, "한글_변수"), List.of(DialogueDisplayPlugin.storedSkriptVariableParts(owner + "::한글 변수")));
        assertEquals(List.of(owner, "점수"), List.of(DialogueDisplayPlugin.storedSkriptVariableParts("rpgmaker::" + owner + "::점수")));
        assertNull(DialogueDisplayPlugin.storedSkriptVariableParts("invalid::점수"));
    }

    @Test
    void templateItemsUseTheViewingPlayerAsOwner() {
        UUID owner = UUID.fromString("12d0a2f0-6e70-4f53-88f7-9571b4c6bced");
        assertEquals("@" + owner + "/회복약:2", DialogueDisplayPlugin.resolveItemOwner("@OWNER/회복약:2", owner));
    }
}
