package kr.hyuni.marketplay;

import java.util.Map;

final class MarketText {
    private MarketText() {}
    static String render(Map<String, Long> prices) {
        return "오늘의 시장\n사과 " + prices.getOrDefault("apple", 0L) + "원 · 참나무 " + prices.getOrDefault("oak_log", 0L)
                + "원\n밀 " + prices.getOrDefault("wheat", 0L) + "원 · 양털 " + prices.getOrDefault("wool", 0L)
                + "원 · 철광석 " + prices.getOrDefault("iron_ore", 0L) + "원\n대구 " + prices.getOrDefault("cod", 0L)
                + "원 · 연어 " + prices.getOrDefault("salmon", 0L) + "원";
    }
}
