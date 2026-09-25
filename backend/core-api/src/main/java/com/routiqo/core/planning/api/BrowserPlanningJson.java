package com.routiqo.core.planning.api;

import com.routiqo.core.planning.domain.AccountPlanningCopy;
import com.routiqo.core.planning.domain.PlanningDocument;
import com.routiqo.core.planning.domain.PlanningMutation;
import com.routiqo.core.planning.domain.PlanningPlan;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact JSON parsing for the account planning write boundary. Unknown or repeated fields, coerced types,
 * non-integral numbers and trailing content are rejected rather than repaired.
 */
final class BrowserPlanningJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final String UUID_TEXT = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}";
    private BrowserPlanningJson() {}

    static PlanningMutation save(InputStream body) {
        try (JsonParser parser = MAPPER.createParser(body)) {
            expect(parser.nextToken(), JsonToken.START_OBJECT);
            List<PlanningPlan> plans = null;
            List<String> saved = null;
            Long expectedVersion = null;
            String mutationId = null;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                expect(token, JsonToken.PROPERTY_NAME);
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (name) {
                    case "plans" -> {
                        if (plans != null) throw invalid();
                        expect(value, JsonToken.START_ARRAY);
                        plans = plans(parser);
                    }
                    case "saved" -> {
                        if (saved != null) throw invalid();
                        expect(value, JsonToken.START_ARRAY);
                        saved = strings(parser, PlanningDocument.MAX_SAVED);
                    }
                    case "expectedVersion" -> {
                        if (expectedVersion != null) throw invalid();
                        expect(value, JsonToken.VALUE_NUMBER_INT);
                        expectedVersion = integer(parser, 0, AccountPlanningCopy.MAX_EXPECTED_VERSION);
                    }
                    case "mutationId" -> {
                        if (mutationId != null) throw invalid();
                        expect(value, JsonToken.VALUE_STRING);
                        mutationId = parser.getString();
                        if (!mutationId.matches(UUID_TEXT)) throw invalid();
                    }
                    default -> throw invalid();
                }
            }
            if (plans == null || saved == null || expectedVersion == null || mutationId == null
                    || parser.nextToken() != null)
                throw invalid();
            return new PlanningMutation(new PlanningDocument(plans, saved), expectedVersion, UUID.fromString(mutationId));
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    static long delete(InputStream body) {
        try (JsonParser parser = MAPPER.createParser(body)) {
            expect(parser.nextToken(), JsonToken.START_OBJECT);
            Long expectedVersion = null;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                expect(token, JsonToken.PROPERTY_NAME);
                if (!"expectedVersion".equals(parser.currentName()) || expectedVersion != null) throw invalid();
                expect(parser.nextToken(), JsonToken.VALUE_NUMBER_INT);
                expectedVersion = integer(parser, 1, AccountPlanningCopy.MAX_VERSION);
            }
            if (expectedVersion == null || parser.nextToken() != null) throw invalid();
            return expectedVersion;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static List<PlanningPlan> plans(JsonParser parser) {
        List<PlanningPlan> plans = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (plans.size() >= PlanningDocument.MAX_PLANS) throw invalid();
            expect(token, JsonToken.START_OBJECT);
            plans.add(plan(parser));
        }
        return plans;
    }

    private static PlanningPlan plan(JsonParser parser) {
        String id = null, kind = null, origin = null, destination = null, date = null, time = null, notes = null,
                createdAt = null;
        List<Integer> days = null;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            expect(token, JsonToken.PROPERTY_NAME);
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            if (name.equals("days")) {
                if (days != null) throw invalid();
                expect(value, JsonToken.START_ARRAY);
                days = days(parser);
                continue;
            }
            expect(value, JsonToken.VALUE_STRING);
            String text = parser.getString();
            switch (name) {
                case "id" -> id = once(id, text);
                case "kind" -> kind = once(kind, text);
                case "origin" -> origin = once(origin, text);
                case "destination" -> destination = once(destination, text);
                case "date" -> date = once(date, text);
                case "time" -> time = once(time, text);
                case "notes" -> notes = once(notes, text);
                case "createdAt" -> createdAt = once(createdAt, text);
                default -> throw invalid();
            }
        }
        if (id == null || kind == null || origin == null || destination == null || date == null || time == null
                || days == null || notes == null || createdAt == null)
            throw invalid();
        return new PlanningPlan(id, PlanningPlan.Kind.fromWire(kind), origin, destination, date, time, days, notes,
                createdAt);
    }

    private static List<Integer> days(JsonParser parser) {
        List<Integer> days = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (days.size() >= 7) throw invalid();
            expect(token, JsonToken.VALUE_NUMBER_INT);
            days.add((int) integer(parser, 0, 6));
        }
        return days;
    }

    private static List<String> strings(JsonParser parser, int maximum) {
        List<String> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (values.size() >= maximum) throw invalid();
            expect(token, JsonToken.VALUE_STRING);
            values.add(parser.getString());
        }
        return values;
    }

    private static long integer(JsonParser parser, long minimum, long maximum) {
        String raw = parser.getString();
        if (raw.length() > 20) throw invalid();
        BigInteger number = new BigInteger(raw);
        if (number.compareTo(BigInteger.valueOf(minimum)) < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0)
            throw invalid();
        return number.longValueExact();
    }

    private static String once(String current, String next) {
        if (current != null) throw invalid();
        return next;
    }

    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid planning request");
    }
}
