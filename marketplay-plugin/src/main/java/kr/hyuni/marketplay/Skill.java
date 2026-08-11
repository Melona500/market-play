package kr.hyuni.marketplay;

public enum Skill {
    FORAGING("채집"), FARMING("농사"), WOODCUTTING("벌목"), FISHING("낚시"), MINING("광업"),
    COOKING("요리"), CARPENTRY("목공"), ALCHEMY("연금술"), JEWELCRAFTING("보석세공");

    private final String displayName;

    Skill(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }

    public static Skill byDisplayName(String value) {
        for (Skill skill : values()) if (skill.displayName.equals(value)) return skill;
        throw new IllegalArgumentException("Unknown skill: " + value);
    }
}
