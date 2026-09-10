package com.routiqo.core.routing.domain;

import java.util.regex.Pattern;

public record PlaceQuery(String text) {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    public PlaceQuery {
        if (text == null) throw new IllegalArgumentException("Enter a place to search");
        text = text.strip();
        if (text.length() < 3 || text.length() > 256 || text.indexOf(';') >= 0
                || text.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a valid place search");
        long words = WORD.matcher(text).results().limit(21).count();
        if (words == 0 || words > 20) throw new IllegalArgumentException("Enter a shorter place search");
    }
    @Override public String toString() { return "PlaceQuery[private]"; }
}
