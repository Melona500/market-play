package kr.hyuni.dialogue;

final class TextWidthRules {
    private static final int FIRST_SPACE_GLYPH = 0xE100;
    private static final int TEXT_SPACE_WIDTH = 3;
    private static final java.util.regex.Pattern VARIABLE_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{.+?}}");

    private TextWidthRules() {}

    static String padding(String text, int targetWidth) {
        return spacing(Math.max(0, targetWidth - visibleWidth(text)));
    }

    private static String spacing(int width) {
        StringBuilder result = new StringBuilder();
        while (width > 0) {
            int chunk = Math.min(width, 511);
            for (int bit = 8; bit >= 0; bit--)
                if ((chunk & (1 << bit)) != 0) result.appendCodePoint(FIRST_SPACE_GLYPH + bit);
            width -= chunk;
        }
        return result.toString();
    }

    static int visibleWidth(String text) {
        int width = 0;
        boolean wordStart = true;
        for (int offset = 0; offset < text.length();) {
            FormatToken inline = inlineFormat(text, offset);
            if (inline != null) {
                offset = inline.end();
                continue;
            }
            FormatToken word = wordStart ? wordFormat(text, offset) : null;
            if (word != null) {
                offset = word.end();
                wordStart = false;
                continue;
            }
            int variableEnd = variableEnd(text, offset);
            if (variableEnd > offset) {
                offset = variableEnd;
                continue;
            }
            int codePoint = text.codePointAt(offset);
            width += glyphWidth(codePoint);
            wordStart = Character.isWhitespace(codePoint);
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    static int visibleCharacters(String text) {
        if (text == null) return 0;
        int visible = 0;
        boolean wordStart = true;
        for (int offset = 0; offset < text.length();) {
            FormatToken inline = inlineFormat(text, offset);
            if (inline != null) {
                offset = inline.end();
                continue;
            }
            FormatToken word = wordStart ? wordFormat(text, offset) : null;
            if (word != null) {
                offset = word.end();
                wordStart = false;
                continue;
            }
            int variableEnd = variableEnd(text, offset);
            if (variableEnd > offset) {
                offset = variableEnd;
                continue;
            }
            int codePoint = text.codePointAt(offset);
            visible++;
            wordStart = Character.isWhitespace(codePoint);
            offset += Character.charCount(codePoint);
        }
        return visible;
    }

    static String limitVisible(String text, int maximum) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        int visible = 0;
        boolean wordStart = true;
        for (int offset = 0; offset < text.length();) {
            FormatToken inline = inlineFormat(text, offset);
            if (inline != null) {
                result.append(text, offset, inline.end());
                offset = inline.end();
                continue;
            }
            FormatToken word = wordStart ? wordFormat(text, offset) : null;
            if (word != null) {
                result.append(text, offset, word.end());
                offset = word.end();
                wordStart = false;
                continue;
            }
            int variableEnd = variableEnd(text, offset);
            if (variableEnd > offset) {
                result.append(text, offset, variableEnd);
                offset = variableEnd;
                continue;
            }
            if (visible >= maximum) break;
            int codePoint = text.codePointAt(offset);
            result.appendCodePoint(codePoint);
            visible++;
            wordStart = Character.isWhitespace(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int glyphWidth(int codePoint) {
        if (codePoint == 0x3000) return 9;
        // The dialogue TextDisplay renders an ordinary U+0020 slightly narrower than the
        // generic whitespace estimate used here previously. Keeping it at 4 made the
        // calculated body width grow too quickly for every authored space, so the trailing
        // fixed-width padding became too short and the apparent left edge drifted by the
        // accumulated error. Calibrate only the normal text space; other whitespace keeps
        // its previous behavior.
        if (codePoint == ' ') return TEXT_SPACE_WIDTH;
        if (Character.isWhitespace(codePoint)) return 4;
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) return 0;
        if (codePoint > 0x7E) return 9;
        if ("!.,:;|i".indexOf(codePoint) >= 0) return 2;
        if ("'`l".indexOf(codePoint) >= 0) return 3;
        if ("I[](){}t".indexOf(codePoint) >= 0) return 4;
        if ("fkr<>".indexOf(codePoint) >= 0) return 5;
        if ("@~".indexOf(codePoint) >= 0) return 7;
        return 6;
    }

    static FormatToken inlineFormat(String text, int offset) {
        if (offset + 9 > text.length() || text.charAt(offset) != '{' || text.charAt(offset + 1) != '#'
                || !hex(text, offset + 2)) return null;
        String color = text.substring(offset + 1, offset + 8);
        if (text.charAt(offset + 8) == '}') return new FormatToken(offset + 9, new TextFormat(color, false, false, false));
        if (text.charAt(offset + 8) != ':') return null;
        int close = text.indexOf('}', offset + 9);
        TextFormat format = close < 0 ? null : format(color, text.substring(offset + 9, close));
        return format == null ? null : new FormatToken(close + 1, format);
    }

    static FormatToken wordFormat(String text, int offset) {
        if (offset + 8 > text.length() || text.charAt(offset) != '#' || !hex(text, offset + 1)
                || text.charAt(offset + 7) != ':') return null;
        String color = text.substring(offset, offset + 7);
        int nextColon = text.indexOf(':', offset + 8);
        if (nextColon > offset + 8) {
            TextFormat styled = format(color, text.substring(offset + 8, nextColon));
            if (styled != null) return new FormatToken(nextColon + 1, styled);
        }
        return new FormatToken(offset + 8, new TextFormat(color, false, false, false));
    }

    private static boolean hex(String text, int offset) {
        if (offset + 6 > text.length()) return false;
        for (int index = offset; index < offset + 6; index++)
            if (Character.digit(text.charAt(index), 16) < 0) return false;
        return true;
    }

    private static TextFormat format(String color, String flags) {
        if (flags.isBlank()) return null;
        boolean bold = false;
        boolean italic = false;
        boolean strikethrough = false;
        for (String flag : flags.split(",")) {
            switch (flag.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "bold" -> bold = true;
                case "italic" -> italic = true;
                case "strikethrough" -> strikethrough = true;
                default -> { return null; }
            }
        }
        return new TextFormat(color, bold, italic, strikethrough);
    }

    private static int variableEnd(String text, int offset) {
        java.util.regex.Matcher matcher = VARIABLE_PLACEHOLDER.matcher(text).region(offset, text.length());
        if (matcher.lookingAt()) return matcher.end();
        if (text.charAt(offset) != '%') return -1;
        int end = text.startsWith("%{", offset) ? text.indexOf("}%", offset + 2) : text.indexOf('%', offset + 1);
        return end < 0 ? -1 : end + (text.startsWith("%{", offset) ? 2 : 1);
    }

    static int paddingWidth(String padding) {
        return padding.codePoints().map(codePoint -> 1 << (codePoint - FIRST_SPACE_GLYPH)).sum();
    }

    public static void main(String[] args) {
        assert visibleWidth("가") > visibleWidth("A");
        assert visibleWidth(" ") == TEXT_SPACE_WIDTH;
        assert visibleWidth("가 나") == 9 + TEXT_SPACE_WIDTH + 9;
        assert visibleWidth("가 나 다") == 9 * 3 + TEXT_SPACE_WIDTH * 2;
        assert visibleWidth("#FF0000:가") == visibleWidth("가");
        assert visibleWidth("{#00FF00}A") == visibleWidth("A");
        assert visibleWidth("#FF0000:bold,italic,strikethrough:가") == visibleWidth("가");
        assert visibleCharacters("123{{long_variable}}{#00FF00}456") == 6;
        assert visibleCharacters("123{{한글_변수}}456") == 6;
        assert visibleCharacters("123{{skript:quest::%uuid of player%}}456") == 6;
        assert visibleCharacters("123%player's location%456") == 6;
        assert visibleCharacters("123%{quest::%uuid of player%}%456") == 6;
        assert visibleCharacters("#FF0000:bold:화자") == 2;
        assert limitVisible("123{{name}}4567", 6).equals("123{{name}}456");
        assert limitVisible("#FF0000:bold:화자이름", 2).equals("#FF0000:bold:화자");
        assert limitVisible("123{{name}}", 3).equals("123{{name}}");
        String padding = padding("한글 English !", 270);
        assert visibleWidth("한글 English !") + paddingWidth(padding) == 270;
        assert paddingWidth(padding("", 270)) == 270;
        assert visibleWidth("aa") + paddingWidth(padding("aa", 270)) == 270;
        assert visibleWidth("[1] 선택") + paddingWidth(padding("[1] 선택", 190)) == 190;
    }

    record TextFormat(String color, boolean bold, boolean italic, boolean strikethrough) {}
    record FormatToken(int end, TextFormat format) {}
}
