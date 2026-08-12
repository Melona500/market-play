package kr.hyuni.marketplay;

final class TutorialProgress {
    static final int NOT_STARTED = 0;
    static final int DIALOGUE = 1;
    static final int OPEN_MENU = 2;
    static final int OPEN_MARKET = 3;
    static final int SELL_SAMPLE = 4;
    static final int COMPLETE = 5;

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
}
