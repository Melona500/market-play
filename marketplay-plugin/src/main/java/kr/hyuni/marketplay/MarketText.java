package kr.hyuni.marketplay;

import java.util.Map;
import java.util.List;

final class MarketText {
    private MarketText() {}
    static String render(Map<String, Long> prices) {
        return "오늘의 시장\n사과 " + prices.getOrDefault("apple", 0L) + "원 · 참나무 " + prices.getOrDefault("oak_log", 0L)
                + "원\n밀 " + prices.getOrDefault("wheat", 0L) + "원 · 양털 " + prices.getOrDefault("wool", 0L)
                + "원 · 철광석 " + prices.getOrDefault("iron_ore", 0L) + "원\n대구 " + prices.getOrDefault("cod", 0L)
                + "원 · 연어 " + prices.getOrDefault("salmon", 0L) + "원";
    }
    static String bulletin(List<String> lines) {
        return String.join("\n", lines.isEmpty() ? List.of("초보자 게시판", "안내인 → 생활도구 상점 → 자원 지역 → 판매대", "/marketplay tools 로 생활도구를 장착하세요", "/marketplay 로 내 진행도를 확인하세요") : lines);
    }
}
