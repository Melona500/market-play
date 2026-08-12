package kr.hyuni.marketplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPlayHelpTest {
    @Test
    void parsesHelpPagesAndRejectsInvalidValues() {
        assertEquals(1, MarketPlayHelp.parsePage(new String[]{"help"}));
        assertEquals(3, MarketPlayHelp.parsePage(new String[]{"help", "3"}));
        assertEquals(-1, MarketPlayHelp.parsePage(new String[]{"help", "0"}));
        assertEquals(-1, MarketPlayHelp.parsePage(new String[]{"help", "6"}));
        assertEquals(-1, MarketPlayHelp.parsePage(new String[]{"help", "abc"}));
    }

    @Test
    void everyPageContainsUsefulEntries() {
        for (int page = 1; page <= MarketPlayHelp.TOTAL_PAGES; page++) {
            assertFalse(MarketPlayHelp.entries(page, false).isEmpty());
        }
        assertTrue(MarketPlayHelp.entries(1, false).stream().anyMatch(entry -> entry.command().equals("/mp market")));
        assertTrue(MarketPlayHelp.entries(3, false).stream().anyMatch(entry -> entry.command().equals("/mp exchange")));
        assertTrue(MarketPlayHelp.entries(4, false).stream().anyMatch(entry -> entry.command().equals("/mp dungeon")));
    }

    @Test
    void adminCommandsAreHiddenFromNormalPlayers() {
        assertFalse(MarketPlayHelp.entries(5, false).stream().anyMatch(entry -> entry.command().startsWith("/mp admin")));
        assertTrue(MarketPlayHelp.entries(5, true).stream().anyMatch(entry -> entry.command().startsWith("/mp admin")));
    }
}
