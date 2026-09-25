package com.routiqo.core.planning.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlanningDocumentTest {
    static PlanningPlan plan(String id) {
        return new PlanningPlan(id, PlanningPlan.Kind.TRIP, "Chennai", "Madurai", "2026-10-02", "06:30", List.of(),
                "Leave early", "2026-09-25T06:00:00.000Z");
    }

    static PlanningPlan commute(String id, List<Integer> days) {
        return new PlanningPlan(id, PlanningPlan.Kind.COMMUTE, "Home", "Office", "2026-09-28", "08:15", days, "",
                "2026-09-25T06:00:00.000Z");
    }

    @Test void acceptsTheBrowserPlanningBoundsExactly() {
        var maximal = new PlanningPlan("i".repeat(100), PlanningPlan.Kind.COMMUTE, "o".repeat(100), "d".repeat(100),
                "2028-02-29", "23:59", List.of(0, 1, 2, 3, 4, 5, 6), "line one\nline two\ttab\r".repeat(30).substring(0, 500),
                "c".repeat(64));
        assertThat(maximal.days()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(new PlanningPlan("id", PlanningPlan.Kind.TRIP, " தமிழ் ", "Pondicherry 🌊", "2026-01-01", "00:00",
                List.of(), "", "x").origin()).isEqualTo(" தமிழ் ");
    }

    @Test void rejectsInvalidPlanFields() {
        List<Runnable> invalid = List.of(
                () -> plan(""),
                () -> plan("i".repeat(101)),
                () -> plan("bad\nid"),
                () -> new PlanningPlan("id", null, "a", "b", "2026-01-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "", "b", "2026-01-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "  　﻿", "b", "2026-01-01", "00:00",
                        List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b".repeat(101), "2026-01-01", "00:00",
                        List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a\tb", "c", "2026-01-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a\ud800", "c", "2026-01-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "\udc00c", "2026-01-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-02-30", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-1-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "+2026-01-01", "00:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "24:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "7:00", List.of(), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(7), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(-1), "", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(1, 1), "", "x"),
                () -> commute("id", List.of()),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(),
                        "n".repeat(501), "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(),
                        "bell\u0007", "x"),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(), "", ""),
                () -> new PlanningPlan("id", PlanningPlan.Kind.TRIP, "a", "b", "2026-01-01", "07:00", List.of(), "",
                        "c".repeat(65)));
        for (int index = 0; index < invalid.size(); index++) {
            assertThatThrownBy(invalid.get(index)::run).as("case %s", index).isInstanceOf(IllegalArgumentException.class);
        }
        var days = new ArrayList<Integer>(); days.add(null);
        assertThatThrownBy(() -> commute("id", days)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void kindUsesExactWireNames() {
        assertThat(PlanningPlan.Kind.fromWire("trip")).isEqualTo(PlanningPlan.Kind.TRIP);
        assertThat(PlanningPlan.Kind.fromWire("commute")).isEqualTo(PlanningPlan.Kind.COMMUTE);
        for (String value : new String[] {"Trip", "TRIP", "", " trip", null})
            assertThatThrownBy(() -> PlanningPlan.Kind.fromWire(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void documentBoundsCountsUniquenessAndSavedIds() {
        List<PlanningPlan> hundred = IntStream.range(0, 100).mapToObj(index -> plan("p" + index)).toList();
        List<String> places = IntStream.range(0, 100).mapToObj(index -> "place-" + index).toList();
        assertThat(new PlanningDocument(hundred, places).plans()).hasSize(100);

        var tooMany = new ArrayList<>(hundred); tooMany.add(plan("p100"));
        var tooManyPlaces = new ArrayList<>(places); tooManyPlaces.add("place-100");
        List<Runnable> invalid = List.of(
                () -> new PlanningDocument(tooMany, List.of()),
                () -> new PlanningDocument(List.of(), tooManyPlaces),
                () -> new PlanningDocument(List.of(plan("same"), plan("same")), List.of()),
                () -> new PlanningDocument(List.of(), List.of("a", "a")),
                () -> new PlanningDocument(List.of(), List.of("Upper")),
                () -> new PlanningDocument(List.of(), List.of("")),
                () -> new PlanningDocument(List.of(), List.of("x".repeat(81))),
                () -> new PlanningDocument(List.of(), List.of("under_score")),
                () -> new PlanningDocument(null, List.of()),
                () -> new PlanningDocument(List.of(), null));
        for (int index = 0; index < invalid.size(); index++) {
            assertThatThrownBy(invalid.get(index)::run).as("case %s", index).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void documentsAreImmutableValuesWithRedactedText() {
        var plans = new ArrayList<PlanningPlan>(); plans.add(plan("one"));
        var document = new PlanningDocument(plans, List.of("kodaikanal"));
        plans.add(plan("two"));
        assertThat(document.plans()).hasSize(1);
        assertThatThrownBy(() -> document.plans().add(plan("three"))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(document).isEqualTo(new PlanningDocument(List.of(plan("one")), List.of("kodaikanal")));
        assertThat(document.toString()).doesNotContain("Chennai", "Madurai", "kodaikanal");
        assertThat(plan("one").toString()).doesNotContain("Chennai", "Leave early");
    }

    @Test void absentCopyIsVersionZeroWithoutTimestampOrContent() {
        assertThat(AccountPlanningCopy.absent().version()).isZero();
        assertThat(AccountPlanningCopy.absent().updatedAt()).isNull();
        var content = new PlanningDocument(List.of(plan("one")), List.of());
        assertThatThrownBy(() -> new AccountPlanningCopy(content, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountPlanningCopy(content, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountPlanningCopy(PlanningDocument.empty(), 0, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountPlanningCopy(content, AccountPlanningCopy.MAX_VERSION + 1, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AccountPlanningCopy(PlanningDocument.empty(), 3, Instant.EPOCH).version()).isEqualTo(3);
    }

    @Test void mutationsNeedAnIdentityAndABoundedExpectedVersion() {
        var document = PlanningDocument.empty();
        assertThatThrownBy(() -> new PlanningMutation(document, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningMutation(document, -1, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanningMutation(document, AccountPlanningCopy.MAX_VERSION, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PlanningMutation(document, AccountPlanningCopy.MAX_EXPECTED_VERSION, UUID.randomUUID())
                .expectedVersion()).isEqualTo(AccountPlanningCopy.MAX_EXPECTED_VERSION);
    }
}
