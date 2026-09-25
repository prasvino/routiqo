package com.routiqo.core.planning.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** One owner-private journey plan, validated to the same bounds as the browser planning store. */
public record PlanningPlan(String id, Kind kind, String origin, String destination, String date, String time,
        List<Integer> days, String notes, String createdAt) {
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");

    public enum Kind {
        TRIP("trip"), COMMUTE("commute");

        private final String wire;
        Kind(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        public static Kind fromWire(String value) {
            for (Kind kind : values()) if (kind.wire.equals(value)) return kind;
            throw new IllegalArgumentException("Unknown plan kind");
        }
    }

    public PlanningPlan {
        id = PlanningText.require(id, 1, 100, false, "Plan id");
        if (kind == null) throw new IllegalArgumentException("Plan kind is required");
        origin = place(origin, "Origin");
        destination = place(destination, "Destination");
        date = date(date);
        if (time == null || !TIME.matcher(time).matches()) throw new IllegalArgumentException("Invalid plan time");
        days = days(days, kind);
        notes = PlanningText.require(notes, 0, 500, true, "Plan notes");
        createdAt = PlanningText.require(createdAt, 1, 64, false, "Plan creation time");
    }

    @Override public String toString() { return "PlanningPlan[private]"; }

    private static String place(String value, String field) {
        PlanningText.require(value, 1, 100, false, field);
        if (PlanningText.blank(value)) throw new IllegalArgumentException(field + " is blank");
        return value;
    }

    private static String date(String value) {
        if (value == null || !DATE.matcher(value).matches()) throw new IllegalArgumentException("Invalid plan date");
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid plan date");
        }
        return value;
    }

    private static List<Integer> days(List<Integer> value, Kind kind) {
        if (value == null || value.size() > 7) throw new IllegalArgumentException("Invalid plan days");
        var seen = new HashSet<Integer>();
        for (Integer day : value) {
            if (day == null || day < 0 || day > 6 || !seen.add(day)) throw new IllegalArgumentException("Invalid plan days");
        }
        if (kind == Kind.COMMUTE && value.isEmpty()) throw new IllegalArgumentException("A commute needs a weekday");
        return List.copyOf(value);
    }
}
