package kr.hyuni.marketplay;

final class TutorialProgress {
    static final int NOT_STARTED = 0;
    static final int LEGACY_GATHER = 1;
    static final int LEGACY_HUNT = 2;
    static final int LEGACY_COMPLETE = 3;

    static final int DIALOGUE = 4;
    static final int OPEN_MENU = 5;
    static final int OPEN_MARKET = 6;
    static final int SELL_SAMPLE = 7;
    static final int COMPLETE = 8;

    enum Action {
        DIALOGUE_COMPLETE,
        MENU_OPENED,
        MARKET_OPENED,
        SAMPLE_SOLD
    }

    private TutorialProgress() {}

    static int advance(int current, Action action) {
        return switch (action) {
            case DIALOGUE_COMPLETE -> current == DIALOGUE ? OPEN_MENU : current;
            case MENU_OPENED -> current == OPEN_MENU ? OPEN_MARKET : current;
            case MARKET_OPENED -> current == OPEN_MARKET ? SELL_SAMPLE : current;
            case SAMPLE_SOLD -> current == SELL_SAMPLE ? COMPLETE : current;
        };
    }

    static boolean active(int step) {
        return step >= DIALOGUE && step < COMPLETE;
    }

    static boolean shouldRestartLegacy(int step) {
        return step == LEGACY_GATHER || step == LEGACY_HUNT;
    }

    static boolean completed(int step) {
        return step == LEGACY_COMPLETE || step >= COMPLETE;
    }
}
