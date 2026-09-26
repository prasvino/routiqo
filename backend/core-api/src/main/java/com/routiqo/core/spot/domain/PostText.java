package com.routiqo.core.spot.domain;

import java.util.regex.Pattern;

/**
 * Post text rules (POSTS_AND_SIGNALS_SPEC, Posts): 1-200 code points after trimming, one paragraph,
 * letters, marks, numbers, punctuation, symbols, spaces and emoji; no control characters; and no
 * links, e-mail addresses or phone numbers (business spam friction). Tamil, English and Tanglish
 * are all accepted, including ZWJ/ZWNJ.
 */
public final class PostText {
    public static final int MAX_CODE_POINTS = 200;
    private static final Pattern CONTACT = Pattern.compile(
            "\\p{Nd}(?:[ \\-]?\\p{Nd}){6,}"
                    + "|(?i:https?://|www\\.|[\\p{L}\\p{Nd}._%+-]+@[\\p{L}\\p{Nd}.-]+\\.[\\p{L}]{2,})"
                    + "|(?i:\\b[\\p{L}\\p{Nd}-]+\\.(?:com|in|net|org|co|io|me|info|biz|app|link|ly)\\b)");

    private PostText() {}

    /** Returns the trimmed text, or throws {@link InvalidPostText} / {@link ContactDetails}. */
    public static String normalize(String raw) {
        if (raw == null) throw new InvalidPostText();
        String text = trim(raw);
        int count = text.codePointCount(0, text.length());
        if (count < 1 || count > MAX_CODE_POINTS) throw new InvalidPostText();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (!allowed(codePoint)) throw new InvalidPostText();
            offset += Character.charCount(codePoint);
        }
        if (CONTACT.matcher(text).find()) throw new ContactDetails();
        return text;
    }

    private static boolean allowed(int codePoint) {
        if (codePoint == ' ' || codePoint == 0x200C || codePoint == 0x200D) return true;
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.SURROGATE, Character.UNASSIGNED, Character.PRIVATE_USE,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT -> false;
            default -> true;
        };
    }

    /** Mirrors JavaScript String.prototype.trim(), so the app and server agree on length. */
    static String trim(String value) {
        int start = 0, end = value.length();
        while (start < end && whitespace(value.charAt(start))) start++;
        while (end > start && whitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean whitespace(char unit) {
        return switch (unit) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F',
                    '\u205F', '\u3000', '\uFEFF' -> true;
            default -> unit >= '\u2000' && unit <= '\u200A';
        };
    }

    public static final class InvalidPostText extends IllegalArgumentException {
        public InvalidPostText() { super("Invalid post text"); }
    }

    /** "Links and phone numbers aren't allowed in posts." */
    public static final class ContactDetails extends IllegalArgumentException {
        public ContactDetails() { super("Post contains contact details"); }
    }
}
