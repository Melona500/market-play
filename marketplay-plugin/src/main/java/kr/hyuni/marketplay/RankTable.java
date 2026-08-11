package kr.hyuni.marketplay;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RankTable {
    private final LinkedHashMap<String, Long> thresholds;

    public RankTable(Map<String, Long> thresholds) {
        if (thresholds.isEmpty()) throw new IllegalArgumentException("At least one rank is required");
        long previous = -1;
        for (long threshold : thresholds.values()) {
            if (threshold < 0 || threshold <= previous) throw new IllegalArgumentException("Rank thresholds must increase");
            previous = threshold;
        }
        this.thresholds = new LinkedHashMap<>(thresholds);
    }

    public String rankFor(long innerPower) {
        String rank = thresholds.keySet().iterator().next();
        for (var entry : thresholds.entrySet()) {
            if (innerPower < entry.getValue()) break;
            rank = entry.getKey();
        }
        return rank;
    }
}
