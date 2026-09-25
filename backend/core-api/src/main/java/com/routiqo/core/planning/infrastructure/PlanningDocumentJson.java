package com.routiqo.core.planning.infrastructure;

import com.routiqo.core.planning.domain.PlanningDocument;
import com.routiqo.core.planning.domain.PlanningPlan;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Canonical storage encoding for the account planning copy. Stored rows are re-validated when read. */
final class PlanningDocumentJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private PlanningDocumentJson() {}

    record Encoded(String plans, String saved, int bytes) {
        @Override public String toString() { return "Encoded[private]"; }
    }

    static Encoded encode(PlanningDocument document) {
        ArrayNode plans = MAPPER.createArrayNode();
        for (PlanningPlan plan : document.plans()) {
            ObjectNode node = plans.addObject();
            node.put("id", plan.id());
            node.put("kind", plan.kind().wire());
            node.put("origin", plan.origin());
            node.put("destination", plan.destination());
            node.put("date", plan.date());
            node.put("time", plan.time());
            ArrayNode days = node.putArray("days");
            for (int day : plan.days()) days.add(day);
            node.put("notes", plan.notes());
            node.put("createdAt", plan.createdAt());
        }
        ArrayNode saved = MAPPER.createArrayNode();
        for (String place : document.saved()) saved.add(place);
        ObjectNode canonical = MAPPER.createObjectNode();
        canonical.set("plans", plans);
        canonical.set("saved", saved);
        int bytes = MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8).length;
        return new Encoded(MAPPER.writeValueAsString(plans), MAPPER.writeValueAsString(saved), bytes);
    }

    static PlanningDocument decode(String plansJson, String savedJson) {
        JsonNode plans = MAPPER.readTree(plansJson);
        JsonNode saved = MAPPER.readTree(savedJson);
        if (!plans.isArray() || !saved.isArray()) throw new IllegalStateException("Stored planning copy is malformed");
        List<PlanningPlan> decodedPlans = new ArrayList<>();
        for (JsonNode plan : plans) {
            List<Integer> days = new ArrayList<>();
            for (JsonNode day : required(plan, "days")) {
                if (!day.isInt()) throw new IllegalStateException("Stored planning copy is malformed");
                days.add(day.intValue());
            }
            decodedPlans.add(new PlanningPlan(text(plan, "id"), PlanningPlan.Kind.fromWire(text(plan, "kind")),
                    text(plan, "origin"), text(plan, "destination"), text(plan, "date"), text(plan, "time"),
                    days, text(plan, "notes"), text(plan, "createdAt")));
        }
        List<String> decodedSaved = new ArrayList<>();
        for (JsonNode place : saved) {
            if (!place.isString()) throw new IllegalStateException("Stored planning copy is malformed");
            decodedSaved.add(place.stringValue());
        }
        return new PlanningDocument(decodedPlans, decodedSaved);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) throw new IllegalStateException("Stored planning copy is malformed");
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isString()) throw new IllegalStateException("Stored planning copy is malformed");
        return value.stringValue();
    }
}
