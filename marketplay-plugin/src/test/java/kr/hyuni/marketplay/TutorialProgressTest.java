package kr.hyuni.marketplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TutorialProgressTest {
    @Test void progressesOnlyInRequiredOrder() {
        int step = TutorialProgress.DIALOGUE;
        assertEquals(step, TutorialProgress.advance(step, TutorialProgress.Action.MARKET_OPENED));
        step = TutorialProgress.advance(step, TutorialProgress.Action.DIALOGUE_COMPLETE);
        assertEquals(TutorialProgress.OPEN_MENU, step);
        step = TutorialProgress.advance(step, TutorialProgress.Action.MENU_OPENED);
        assertEquals(TutorialProgress.OPEN_MARKET, step);
        step = TutorialProgress.advance(step, TutorialProgress.Action.MARKET_OPENED);
        assertEquals(TutorialProgress.SELL_SAMPLE, step);
        step = TutorialProgress.advance(step, TutorialProgress.Action.SAMPLE_SOLD);
        assertEquals(TutorialProgress.COMPLETE, step);
        assertTrue(TutorialProgress.completed(step));
        assertFalse(TutorialProgress.active(step));
    }

    @Test void preservesLegacyCompletionAndRestartsOnlyIncompleteLegacySteps() {
        assertTrue(TutorialProgress.shouldRestartLegacy(TutorialProgress.LEGACY_GATHER));
        assertTrue(TutorialProgress.shouldRestartLegacy(TutorialProgress.LEGACY_HUNT));
        assertFalse(TutorialProgress.shouldRestartLegacy(TutorialProgress.LEGACY_COMPLETE));
        assertTrue(TutorialProgress.completed(TutorialProgress.LEGACY_COMPLETE));
        assertFalse(TutorialProgress.active(TutorialProgress.LEGACY_COMPLETE));
    }
}
