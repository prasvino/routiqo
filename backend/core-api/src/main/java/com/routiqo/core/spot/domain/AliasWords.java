package com.routiqo.core.spot.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * The versioned alias word list (POSTS_AND_SIGNALS_SPEC, Aliases and rooms): an adjective and a
 * noun, never derived from the account. A pair taken in the room gets a number from 2 to 99.
 */
public record AliasWords(String version, List<String> adjectives, List<String> nouns) {
    private static final int ATTEMPTS = 40;

    public AliasWords {
        if (version == null || !version.matches("[a-z0-9][a-z0-9.-]{0,31}") || adjectives == null
                || nouns == null || adjectives.size() < 20 || nouns.size() < 20)
            throw new IllegalArgumentException("Invalid alias word list");
        adjectives = List.copyOf(adjectives);
        nouns = List.copyOf(nouns);
        var seen = new HashSet<String>();
        var all = new ArrayList<String>(adjectives);
        all.addAll(nouns);
        for (String word : all)
            if (!word.matches("[A-Z][a-z]{1,15}") || !seen.add(word.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Invalid alias word list");
    }

    /** Parses the resource: `[adjectives]` and `[nouns]` sections, one word per line, `#` comments. */
    public static AliasWords parse(String text) {
        String version = null;
        List<String> adjectives = new ArrayList<>(), nouns = new ArrayList<>();
        List<String> current = null;
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("# version:")) version = line.substring("# version:".length()).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("[adjectives]")) current = adjectives;
            else if (line.equals("[nouns]")) current = nouns;
            else if (current == null) throw new IllegalArgumentException("Invalid alias word list");
            else current.add(line);
        }
        return new AliasWords(version, adjectives, nouns);
    }

    /**
     * Picks a random pair not already taken in the room; if the plain pair is taken, tries numbered
     * variants. {@code random} returns an index in [0, bound).
     */
    public String pick(IntUnaryOperator random, Predicate<String> taken) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String pair = adjectives.get(random.applyAsInt(adjectives.size())) + " "
                    + nouns.get(random.applyAsInt(nouns.size()));
            if (!taken.test(pair)) return pair;
            String numbered = pair + " " + (2 + random.applyAsInt(98));
            if (!taken.test(numbered)) return numbered;
        }
        throw new IllegalStateException("No alias available");
    }

    /** True when the alias is a pair from this list, with an optional number from 2 to 99. */
    public boolean issued(String alias) {
        String[] parts = alias.split(" ");
        if (parts.length < 2 || parts.length > 3) return false;
        if (parts.length == 3 && !parts[2].matches("[2-9]|[1-9][0-9]")) return false;
        return adjectives.contains(parts[0]) && nouns.contains(parts[1]);
    }
}
