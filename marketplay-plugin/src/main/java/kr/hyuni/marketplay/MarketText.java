package kr.hyuni.marketplay;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

final class MarketText {
    private MarketText() {}
    static String render(Map<String, Long> prices) {
        return "오늘의 시장\n사과 " + prices.getOrDefault("apple", 0L) + "원 · 참나무 " + prices.getOrDefault("oak_log", 0L)
                + "원\n밀 " + prices.getOrDefault("wheat", 0L) + "원 · 양털 " + prices.getOrDefault("wool", 0L)
                + "원 · 철광석 " + prices.getOrDefault("iron_ore", 0L) + "원\n대구 " + prices.getOrDefault("cod", 0L)
                + "원 · 연어 " + prices.getOrDefault("salmon", 0L) + "원";
    }
    static String render(MarketDay market) {
        Map<String, String> names = Map.of("apple", "사과", "oak_log", "참나무", "wheat", "밀", "wool", "양털", "iron_ore", "철광석", "cod", "대구", "salmon", "연어");
        List<String> lines = new ArrayList<>();
        lines.add("오늘의 시장 · " + market.date());
        market.entries().forEach((id, entry) -> lines.add((entry.changePercent() >= 0 ? "▲ " : "▼ ") + names.getOrDefault(id, id) + " " + entry.unitPrice() + "원 (" + (entry.changePercent() >= 0 ? "+" : "") + entry.changePercent() + "%)"));
        MarketDay.Entry royal = market.entries().get(market.royalItem());
        lines.add("왕실 특별 주문 · " + names.getOrDefault(market.royalItem(), market.royalItem()) + " ×" + royal.royalTarget());
        return String.join("\n", lines);
    }
    static String bulletin(List<String> lines) {
        return String.join("\n", lines.isEmpty() ? List.of("초보자 게시판", "안내인 → 생활도구 상점 → 자원 지역 → 판매대", "/marketplay tools 로 생활도구 소유권을 확인하세요", "/marketplay 로 내 진행도를 확인하세요") : lines);
    }
    static String bulletinPosts(List<ProfileStore.BulletinPost> posts) {
        List<String> lines = new ArrayList<>(List.of("플레이어 게시판", "/mp board post <글> · /mp board remove <번호>"));
        posts.forEach(post -> lines.add("[" + post.shortId() + "] " + post.authorName() + ": " + post.body()));
        if (posts.isEmpty()) lines.add("등록된 글이 없습니다.");
        return String.join("\n", lines);
    }
}
