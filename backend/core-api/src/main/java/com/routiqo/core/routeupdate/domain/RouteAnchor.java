package com.routiqo.core.routeupdate.domain;

import com.routiqo.core.routeupdate.domain.QuickSignalValue.Category;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable curated route relevance input. It is not evidence of physical presence. */
public record RouteAnchor(UUID anchorId, RouteRequest.Coordinate location,
        Set<Category> categories, Optional<String> displayLabel) {
    private static final UUID NIL_ID = new UUID(0, 0);

    public RouteAnchor(UUID anchorId, RouteRequest.Coordinate location, Set<Category> categories) {
        this(anchorId, location, categories, Optional.empty());
    }

    public RouteAnchor {
        if (anchorId == null || NIL_ID.equals(anchorId) || location == null || categories == null
                || categories.isEmpty() || categories.size() > Category.values().length
                || categories.stream().anyMatch(java.util.Objects::isNull) || displayLabel == null
                || displayLabel.isPresent() && !validLabel(displayLabel.orElseThrow())) {
            throw new IllegalArgumentException("Invalid route anchor");
        }
        categories = Set.copyOf(categories);
    }

    private static boolean validLabel(String label) {
        int count = label.codePointCount(0, label.length());
        if (count < 1 || count > 80 || label.charAt(0) == ' '
                || label.charAt(label.length() - 1) == ' ') return false;
        return label.codePoints().allMatch(RouteAnchor::allowedLabelCodePoint);
    }

    private static boolean allowedLabelCodePoint(int codePoint) {
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

    @Override public String toString() { return "RouteAnchor[private]"; }
}
