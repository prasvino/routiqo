package com.routiqo.core.journal.api;

import com.routiqo.core.journal.domain.JournalAnnotation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, exact JSON parsing for the native journal write boundary. */
final class NativeJournalJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private NativeJournalJson() {}

    record SaveRequest(String title, String notes, long expectedVersion, UUID mutationId) {
        @Override public String toString() { return "SaveRequest[private]"; }
    }

    static SaveRequest save(HttpServletRequest request) {
        try (JsonParser parser = MAPPER.createParser(request.getInputStream())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
            String title = null, notes = null, mutation = null;
            Long version = null;
            boolean titleSeen = false, notesSeen = false, versionSeen = false, mutationSeen = false;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) throw invalid();
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) throw invalid();
                switch (name) {
                    case "title" -> {
                        if (titleSeen || value != JsonToken.VALUE_STRING) throw invalid();
                        titleSeen = true; title = parser.getString();
                    }
                    case "notes" -> {
                        if (notesSeen || value != JsonToken.VALUE_STRING) throw invalid();
                        notesSeen = true; notes = parser.getString();
                    }
                    case "expectedVersion" -> {
                        if (versionSeen || !value.isNumeric()) throw invalid();
                        versionSeen = true; version = version(parser.getString());
                    }
                    case "mutationId" -> {
                        if (mutationSeen || value != JsonToken.VALUE_STRING) throw invalid();
                        mutationSeen = true; mutation = parser.getString();
                    }
                    default -> throw invalid();
                }
            }
            if (!titleSeen || !notesSeen || !versionSeen || !mutationSeen || parser.nextToken() != null)
                throw invalid();
            if (mutation == null || !mutation.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                throw invalid();
            return new SaveRequest(title, notes, version, UUID.fromString(mutation));
        } catch (IOException | RuntimeException error) {
            throw invalid();
        }
    }

    private static long version(String raw) {
        if (raw.length() > 64 || exponentMagnitude(raw) > 100) throw invalid();
        final BigDecimal number;
        try { number = new BigDecimal(raw); }
        catch (NumberFormatException error) { throw invalid(); }
        if (number.stripTrailingZeros().scale() > 0 || number.compareTo(BigDecimal.ZERO) < 0
                || number.compareTo(BigDecimal.valueOf(JournalAnnotation.MAX_EXPECTED_VERSION)) > 0)
            throw invalid();
        try { return number.longValueExact(); }
        catch (ArithmeticException error) { throw invalid(); }
    }

    private static int exponentMagnitude(String raw) {
        int marker = Math.max(raw.indexOf('e'), raw.indexOf('E'));
        if (marker < 0) return 0;
        String exponent = raw.substring(marker + 1);
        if (exponent.startsWith("+") || exponent.startsWith("-")) exponent = exponent.substring(1);
        if (exponent.length() > 3) return 101;
        try { return Integer.parseInt(exponent); }
        catch (NumberFormatException error) { return 101; }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native journal request"); }
}
