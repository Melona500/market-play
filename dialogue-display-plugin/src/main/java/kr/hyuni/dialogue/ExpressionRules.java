package kr.hyuni.dialogue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExpressionRules {
    private static final Pattern RANDOM_RANGE = Pattern.compile(
            "(?i)^random\\(\\s*(-?\\d+)\\s*\\.\\.\\s*(-?\\d+)\\s*\\)$");

    private ExpressionRules() {}

    static boolean compare(String actual, String operator, String expected) {
        String op = operator == null ? "EQ" : operator.toUpperCase(java.util.Locale.ROOT);
        boolean unset = actual == null;
        if (op.equals("IS_SET")) return !unset;
        if (op.equals("IS_UNSET")) return unset;
        boolean expectedUnset = expected == null || List.of("null", "none", "미설정").contains(expected.strip().toLowerCase(java.util.Locale.ROOT));
        if (expectedUnset) return op.equals("NE") ? !unset : unset;
        if (unset) return op.equals("NE");
        int order;
        try { order = new BigDecimal(actual.strip()).compareTo(new BigDecimal(expected.strip())); }
        catch (NumberFormatException ignored) { order = actual.compareTo(expected); }
        return switch (op) {
            case "NE" -> order != 0;
            case "GT" -> order > 0;
            case "GTE" -> order >= 0;
            case "LT" -> order < 0;
            case "LTE" -> order <= 0;
            default -> actual.equalsIgnoreCase(expected);
        };
    }

    static boolean combine(List<Boolean> values, String logic) {
        if (values.isEmpty()) return false;
        return switch (logic == null ? "AND" : logic.toUpperCase(java.util.Locale.ROOT)) {
            case "OR" -> values.stream().anyMatch(Boolean::booleanValue);
            case "XOR" -> values.stream().filter(Boolean::booleanValue).count() == 1;
            case "NOT" -> values.stream().noneMatch(Boolean::booleanValue);
            default -> values.stream().allMatch(Boolean::booleanValue);
        };
    }

    static String conditionalText(boolean matches, String original, String replacement) {
        return !matches && replacement != null && !replacement.isBlank() ? replacement : original;
    }

    static String calculate(String current, String operator, String operand) {
        String value = resolveOperand(operand);
        if (operator == null || operator.equals("="))
            return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") ? value.toLowerCase(java.util.Locale.ROOT) : value;
        if (operator.equals("+=") && (!isNumber(current) || !isNumber(value)))
            return (current == null ? "" : current) + value;
        try {
            BigDecimal left = new BigDecimal(current == null || current.isBlank() ? "0" : current.strip());
            BigDecimal right = new BigDecimal(value);
            BigDecimal result = switch (operator) {
                case "+=" -> left.add(right);
                case "-=" -> left.subtract(right);
                case "*=" -> left.multiply(right);
                case "/=" -> right.compareTo(BigDecimal.ZERO) == 0 ? left : left.divide(right, 8, RoundingMode.HALF_UP);
                default -> right;
            };
            return result.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) { return current == null ? "" : current; }
    }

    static boolean isRandomRange(String operand) {
        if (operand == null) return false;
        return RANDOM_RANGE.matcher(operand.strip()).matches();
    }

    static String resolveOperand(String operand) {
        String value = operand == null ? "" : operand.strip();
        Matcher matcher = RANDOM_RANGE.matcher(value);
        if (!matcher.matches()) return value;
        try {
            long first = Long.parseLong(matcher.group(1));
            long second = Long.parseLong(matcher.group(2));
            long minimum = Math.min(first, second);
            long maximum = Math.max(first, second);
            if (minimum == maximum) return Long.toString(minimum);
            return Long.toString(nextLongInclusive(minimum, maximum));
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    static Object typedValue(String value) {
        String text = value == null ? "" : value.strip();
        try {
            if (text.matches("[+-]?\\d+")) return Long.parseLong(text);
            if (text.matches("[+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+)")) return Double.parseDouble(text);
        } catch (NumberFormatException ignored) { }
        return value == null ? "" : value;
    }

    private static long nextLongInclusive(long minimum, long maximum) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (minimum == Long.MIN_VALUE && maximum == Long.MAX_VALUE) return random.nextLong();
        if (maximum != Long.MAX_VALUE) return random.nextLong(minimum, maximum + 1L);

        // nextLong(origin, bound) cannot represent Long.MAX_VALUE + 1. Rejection
        // sampling keeps the inclusive top endpoint unbiased without overflowing.
        long candidate;
        do {
            candidate = random.nextLong();
        } while (candidate < minimum);
        return candidate;
    }

    private static boolean isNumber(String value) {
        if (value == null || value.isBlank()) return false;
        try { new BigDecimal(value.strip()); return true; }
        catch (NumberFormatException ignored) { return false; }
    }

    public static void main(String[] args) {
        assert compare(null, "IS_UNSET", "");
        assert compare("10", "GT", "2");
        assert combine(List.of(true, false), "XOR");
        assert conditionalText(true, "original", "replacement").equals("original");
        assert conditionalText(false, "original", "replacement").equals("replacement");
        assert conditionalText(false, "original", "").equals("original");
        assert calculate("5", "+=", "3").equals("8");
        assert calculate("true", "=", "FALSE").equals("false");
        assert isRandomRange("random(3..7)");
        assert isRandomRange("RANDOM(-7..-3)");
        assert resolveOperand("random(4..4)").equals("4");
        assert typedValue("123") instanceof Long;
        assert typedValue("12.5") instanceof Double;
        assert typedValue("한글").equals("한글");
        for (int index = 0; index < 100; index++) {
            long random = Long.parseLong(resolveOperand("random(7..3)"));
            assert random >= 3 && random <= 7;
        }
    }
}
