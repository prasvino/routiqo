package com.routiqo.core.planning.domain;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** The complete planning state a traveller explicitly keeps on their account. */
public record PlanningDocument(List<PlanningPlan> plans, List<String> saved) {
    public static final int MAX_PLANS = 100;
    public static final int MAX_SAVED = 100;
    /** Upper bound for the stored canonical UTF-8 JSON document. */
    public static final int MAX_BYTES = 256 * 1024;
    private static final Pattern SAVED_ID = Pattern.compile("[a-z0-9-]{1,80}");

    public PlanningDocument {
        if (plans == null || plans.size() > MAX_PLANS) throw new IllegalArgumentException("Invalid plan count");
        var ids = new HashSet<String>();
        for (PlanningPlan plan : plans) {
            if (plan == null || !ids.add(plan.id())) throw new IllegalArgumentException("Plan ids must be unique");
        }
        if (saved == null || saved.size() > MAX_SAVED) throw new IllegalArgumentException("Invalid saved place count");
        var places = new HashSet<String>();
        for (String place : saved) {
            if (place == null || !SAVED_ID.matcher(place).matches() || !places.add(place))
                throw new IllegalArgumentException("Invalid saved place");
        }
        plans = List.copyOf(plans);
        saved = List.copyOf(saved);
    }

    public static PlanningDocument empty() {
        return new PlanningDocument(List.of(), List.of());
    }

    public boolean isEmpty() {
        return plans.isEmpty() && saved.isEmpty();
    }

    @Override public String toString() { return "PlanningDocument[private]"; }
}
