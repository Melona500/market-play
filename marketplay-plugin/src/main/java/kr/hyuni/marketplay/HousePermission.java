package kr.hyuni.marketplay;

enum HousePermission {
    VISIT(1), FURNITURE(2), FOOD(4), STORAGE(8), PLACE(16), BUILD(32), INVITE(64);

    final int bit;
    HousePermission(int bit) { this.bit = bit; }
}
