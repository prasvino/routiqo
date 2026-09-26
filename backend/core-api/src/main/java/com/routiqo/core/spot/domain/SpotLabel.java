package com.routiqo.core.spot.domain;

/**
 * The display-label rule for Spot and corridor names: 1-80 code points of letters, marks, numbers,
 * punctuation and symbols, single interior spaces only. Mirrors {@code RouteAnchor.validDisplayLabel}
 * so the Spot module does not depend on archived route-anchor code.
 */
public final class SpotLabel {
    private SpotLabel() {}

    public static boolean valid(String label) {
        if (label == null) return false;
        int count = label.codePointCount(0, label.length());
        if (count < 1 || count > 80 || label.charAt(0) == ' '
                || label.charAt(label.length() - 1) == ' ' || label.contains("  ")) return false;
        return label.codePoints().allMatch(SpotLabel::allowed);
    }

    private static boolean allowed(int codePoint) {
        if (codePoint == ' ') return true;
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER,
                    Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER,
                    Character.NON_SPACING_MARK,
                    Character.ENCLOSING_MARK,
                    Character.COMBINING_SPACING_MARK,
                    Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER,
                    Character.OTHER_NUMBER,
                    Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }
}
