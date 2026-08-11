package kr.hyuni.marketplay;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerProfile {
    private final UUID playerId;
    private long money;
    private long innerPower;
    private double vitality;
    private int deepOmen;
    private int royalReputation;
    private String knightState = "NONE";
    private final EnumMap<Skill, Long> experience = new EnumMap<>(Skill.class);
    private final Set<String> tools = new HashSet<>();

    public PlayerProfile(UUID playerId, long money, long innerPower, double vitality) {
        this.playerId = playerId;
        this.money = Math.max(0, money);
        this.innerPower = Math.max(0, innerPower);
        this.vitality = Math.max(0, vitality);
        for (Skill skill : Skill.values()) experience.put(skill, 0L);
    }

    public UUID playerId() { return playerId; }
    public long money() { return money; }
    public long innerPower() { return innerPower; }
    public double vitality() { return vitality; }
    public int deepOmen() { return deepOmen; }
    public int royalReputation() { return royalReputation; }
    public String knightState() { return knightState; }
    public long experience(Skill skill) { return experience.get(skill); }
    public int level(Skill skill) { return (int) Math.sqrt(experience(skill) / 25.0); }
    public Map<Skill, Long> experience() { return Map.copyOf(experience); }
    public Set<String> tools() { return Set.copyOf(tools); }
    public boolean hasTool(String toolId) { return tools.contains(toolId); }

    public void setExperience(Skill skill, long value) { experience.put(skill, Math.max(0, value)); }
    public void addExperience(Skill skill, long value) { setExperience(skill, experience(skill) + Math.max(0, value)); }
    public void addMoney(long value) { money = Math.max(0, Math.addExact(money, value)); }
    public void addInnerPower(long value) { innerPower = Math.max(0, Math.addExact(innerPower, value)); }
    public void setDeepOmen(int value) { deepOmen = Math.max(0, Math.min(100, value)); }
    public void addRoyalReputation(int value) { royalReputation = Math.max(0, Math.addExact(royalReputation, value)); }
    void setRoyalReputation(int value) { royalReputation = Math.max(0, value); }
    public void setKnightState(String value) {
        if (!Set.of("NONE", "ARCHERY", "DUEL", "APPRENTICE").contains(value)) throw new IllegalArgumentException("Unknown knight state: " + value);
        knightState = value;
    }
    public void addTool(String toolId) { tools.add(toolId); }
    public boolean spendVitality(double value) {
        if (value < 0 || vitality < value) return false;
        vitality -= value;
        return true;
    }
    public void restoreVitality(double value, double maximum) { vitality = Math.min(maximum, vitality + Math.max(0, value)); }

    PlayerProfile copy() {
        PlayerProfile copy = new PlayerProfile(playerId, money, innerPower, vitality);
        experience.forEach(copy::setExperience);
        tools.forEach(copy::addTool);
        copy.setDeepOmen(deepOmen);
        copy.addRoyalReputation(royalReputation);
        copy.setKnightState(knightState);
        return copy;
    }

    void restore(PlayerProfile source) {
        money = source.money;
        innerPower = source.innerPower;
        vitality = source.vitality;
        experience.clear();
        experience.putAll(source.experience);
        tools.clear();
        tools.addAll(source.tools);
        deepOmen = source.deepOmen;
        royalReputation = source.royalReputation;
        knightState = source.knightState;
    }

    void setMoney(long value) { money = value; }
}
