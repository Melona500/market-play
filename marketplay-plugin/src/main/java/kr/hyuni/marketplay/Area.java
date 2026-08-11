package kr.hyuni.marketplay;

record Area(int minX, int minZ, int maxX, int maxZ) {
    boolean contains(String world, int x, int y, int z) { return "world".equals(world) && y >= HubBuilder.FLOOR_Y - 2 && y <= HubBuilder.FLOOR_Y + 16 && x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
}
