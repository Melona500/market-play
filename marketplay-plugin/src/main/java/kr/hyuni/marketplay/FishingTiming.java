package kr.hyuni.marketplay;

final class FishingTiming {
    private FishingTiming() {}
    static boolean caught(Long deadline, long now) { return deadline != null && now <= deadline; }
}
