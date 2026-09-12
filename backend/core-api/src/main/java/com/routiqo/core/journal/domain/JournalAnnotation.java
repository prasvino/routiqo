package com.routiqo.core.journal.domain;

import java.time.Instant;
import java.util.Objects;

public record JournalAnnotation(String title, String notes, long version, Instant updatedAt) {
    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    public static final long MAX_EXPECTED_VERSION = MAX_VERSION - 1;

    public JournalAnnotation {
        title = validateText(Objects.requireNonNull(title), 120, false);
        notes = validateText(Objects.requireNonNull(notes), 4_000, true);
        if (version < 0 || version > MAX_VERSION) throw new IllegalArgumentException("Invalid annotation version");
        if ((version == 0) != (updatedAt == null)) throw new IllegalArgumentException("Invalid annotation timestamp");
        if (version == 0 && (!title.isEmpty() || !notes.isEmpty()))
            throw new IllegalArgumentException("A derived annotation must be empty");
    }

    public static JournalAnnotation empty() {
        return new JournalAnnotation("", "", 0, null);
    }

    @Override public String toString() { return "JournalAnnotation[private]"; }

    public static String validateTitle(String value) {
        if (value == null) throw new IllegalArgumentException("Journal title is required");
        return validateText(value, 120, false);
    }

    public static String validateNotes(String value) {
        if (value == null) throw new IllegalArgumentException("Journal notes are required");
        return validateText(value, 4_000, true);
    }

    private static String validateText(String value, int maximumLength, boolean multiline) {
        if (value.length() > maximumLength) throw new IllegalArgumentException("Journal text is too long");
        for (int offset = 0; offset < value.length();) {
            char unit = value.charAt(offset);
            if (Character.isHighSurrogate(unit)) {
                if (offset + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(offset + 1)))
                    throw new IllegalArgumentException("Journal text contains invalid Unicode");
                offset += 2;
                continue;
            }
            if (Character.isLowSurrogate(unit))
                throw new IllegalArgumentException("Journal text contains invalid Unicode");
            int codePoint = unit;
            if (Character.isISOControl(codePoint)
                    && !(multiline && (codePoint == '\t' || codePoint == '\n' || codePoint == '\r')))
                throw new IllegalArgumentException("Journal text contains a control character");
            offset++;
        }
        return value;
    }
}
