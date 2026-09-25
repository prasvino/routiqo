package com.routiqo.core.planning.domain;

/** Text rules shared by planning fields. Lengths count UTF-16 code units to match the browser client. */
final class PlanningText {
    private PlanningText() {}

    static String require(String value, int minimumLength, int maximumLength, boolean multiline, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        if (value.length() < minimumLength || value.length() > maximumLength)
            throw new IllegalArgumentException(field + " has an invalid length");
        for (int offset = 0; offset < value.length();) {
            char unit = value.charAt(offset);
            if (Character.isHighSurrogate(unit)) {
                if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1)))
                    throw new IllegalArgumentException(field + " contains invalid Unicode");
                offset += 2;
                continue;
            }
            if (Character.isLowSurrogate(unit)) throw new IllegalArgumentException(field + " contains invalid Unicode");
            if (Character.isISOControl(unit) && !(multiline && (unit == '\t' || unit == '\n' || unit == '\r')))
                throw new IllegalArgumentException(field + " contains a control character");
            offset++;
        }
        return value;
    }

    /** Mirrors JavaScript String.prototype.trim(): true when only ECMAScript whitespace/line terminators remain. */
    static boolean blank(String value) {
        for (int offset = 0; offset < value.length(); offset++) {
            if (!ecmaWhitespace(value.charAt(offset))) return false;
        }
        return true;
    }

    /** Mirrors JavaScript String.prototype.trim(). */
    static String trim(String value) {
        int start = 0, end = value.length();
        while (start < end && ecmaWhitespace(value.charAt(start))) start++;
        while (end > start && ecmaWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean ecmaWhitespace(char unit) {
        return switch (unit) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F',
                    '\u205F', '\u3000', '\uFEFF' -> true;
            default -> unit >= '\u2000' && unit <= '\u200A';
        };
    }
}
