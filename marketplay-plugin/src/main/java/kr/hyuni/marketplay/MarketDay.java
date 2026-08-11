package kr.hyuni.marketplay;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

record MarketDay(LocalDate date, Map<String, Entry> entries) {
    MarketDay { entries = Map.copyOf(entries); }

    static MarketDay create(LocalDate date, Map<String, Long> bases, Map<String, Long> supplies) {
        var ids = bases.keySet().stream().sorted().toList();
        if (ids.isEmpty()) throw new IllegalArgumentException("판매 기준가가 없습니다.");
        String royal = ids.get(Math.floorMod(Long.hashCode(date.toEpochDay()), ids.size()));
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String id : ids) {
            long base = bases.get(id);
            long sold = Math.max(0, supplies.getOrDefault(id, 0L));
            int event = Math.floorMod((date + ":" + id).hashCode(), 21) - 10;
            int change = event - (int) Math.min(30, sold / 20) + (id.equals(royal) ? 50 : 0);
            long price = Math.max(1, Math.multiplyExact(base, 100L + change) / 100L);
            int target = id.equals(royal) ? 100 + Math.floorMod((id + date).hashCode(), 5) * 50 : 0;
            entries.put(id, new Entry(price, change, sold, target));
        }
        return new MarketDay(date, entries);
    }

    Map<String, Long> prices() {
        Map<String, Long> result = new LinkedHashMap<>();
        entries.forEach((id, entry) -> result.put(id, entry.unitPrice()));
        return Map.copyOf(result);
    }

    String royalItem() { return entries.entrySet().stream().filter(entry -> entry.getValue().royalTarget() > 0).map(Map.Entry::getKey).findFirst().orElseThrow(); }
    record Entry(long unitPrice, int changePercent, long soldRecent, int royalTarget) {}
}
