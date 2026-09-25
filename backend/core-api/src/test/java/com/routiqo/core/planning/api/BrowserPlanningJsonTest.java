package com.routiqo.core.planning.api;

import com.routiqo.core.planning.domain.PlanningPlan;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BrowserPlanningJsonTest {
    static final String MUTATION = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final String PLAN = """
        {"id":"p1","kind":"commute","origin":"Navalur","destination":"DLF Chennai","date":"2026-09-28",\
        "time":"08:15","days":[1,2,3,4,5],"notes":"Line\\nTwo","createdAt":"2026-09-25T06:00:00.000Z"}""";

    static ByteArrayInputStream body(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    static String write(String plans, String saved, String version, String mutation) {
        return "{\"plans\":" + plans + ",\"saved\":" + saved + ",\"expectedVersion\":" + version
                + ",\"mutationId\":" + mutation + "}";
    }

    @Test void parsesACompleteWriteExactly() {
        var mutation = BrowserPlanningJson.save(body(write("[" + PLAN + "]", "[\"kodaikanal\",\"ooty\"]", "4",
                "\"" + MUTATION + "\"")));
        assertThat(mutation.expectedVersion()).isEqualTo(4);
        assertThat(mutation.mutationId()).hasToString(MUTATION);
        assertThat(mutation.document().saved()).containsExactly("kodaikanal", "ooty");
        PlanningPlan plan = mutation.document().plans().getFirst();
        assertThat(plan.kind()).isEqualTo(PlanningPlan.Kind.COMMUTE);
        assertThat(plan.days()).containsExactly(1, 2, 3, 4, 5);
        assertThat(plan.notes()).isEqualTo("Line\nTwo");
        assertThat(BrowserPlanningJson.save(body(write("[]", "[]", "0", "\"" + MUTATION + "\""))).document().isEmpty())
                .isTrue();
    }

    @Test void rejectsCoercionUnknownDuplicateAndTrailingInput() {
        String plans = "[" + PLAN + "]";
        String id = "\"" + MUTATION + "\"";
        List<String> invalid = List.of(
                "", "null", "[]", "{}", "{not json}",
                write(plans, "[]", "0", id) + " {}",
                write(plans, "[]", "0", id).replace("}", ",\"extra\":1}"),
                write(plans, "[]", "0", id).replace("\"saved\":[]", "\"saved\":[],\"saved\":[]"),
                write(plans, "[]", "\"0\"", id),
                write(plans, "[]", "0.0", id),
                write(plans, "[]", "1e0", id),
                write(plans, "[]", "-1", id),
                write(plans, "[]", "9007199254740991", id),
                write(plans, "[]", "123456789012345678901234567890", id),
                write(plans, "[]", "true", id),
                write(plans, "[]", "0", "\"" + MUTATION.toUpperCase() + "\""),
                write(plans, "[]", "0", "\"not-a-uuid\""),
                write(plans, "[]", "0", "17"),
                write("{}", "[]", "0", id),
                write(plans, "{}", "0", id),
                write(plans, "[1]", "0", id),
                write(plans, "[\"Bad\"]", "0", id),
                write(plans, "[\"a\",\"a\"]", "0", id),
                write("[null]", "[]", "0", id),
                write("[" + PLAN + "," + PLAN + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"days\":[1,2,3,4,5]", "\"days\":[1.0]") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"days\":[1,2,3,4,5]", "\"days\":[\"1\"]") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"days\":[1,2,3,4,5]", "\"days\":[0,1,2,3,4,5,6,0]") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"days\":[1,2,3,4,5]", "\"days\":[]") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"kind\":\"commute\"", "\"kind\":\"Commute\"") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"notes\":\"Line\\nTwo\"", "\"notes\":7") + "]", "[]", "0", id),
                write("[" + PLAN.replace(",\"notes\":\"Line\\nTwo\"", "") + "]", "[]", "0", id),
                write("[" + PLAN.replace("}", ",\"extra\":\"x\"}") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"id\":\"p1\"", "\"id\":\"p1\",\"id\":\"p2\"") + "]", "[]", "0", id),
                write("[" + PLAN.replace("\"origin\":\"Navalur\"", "\"origin\":\"\\ud800\"") + "]", "[]", "0", id),
                "{\"plans\":[],\"saved\":[],\"expectedVersion\":0}",
                "{\"plans\":[],\"expectedVersion\":0,\"mutationId\":" + id + "}");
        for (String json : invalid) {
            assertThatThrownBy(() -> BrowserPlanningJson.save(body(json)))
                    .as(json.length() < 300 ? json : "long input").isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid planning request");
        }
    }

    @Test void rejectsMoreThanTheDocumentBoundsWhileParsing() {
        StringBuilder plans = new StringBuilder("[");
        for (int index = 0; index <= 100; index++) {
            if (index > 0) plans.append(',');
            plans.append(PLAN.replace("\"id\":\"p1\"", "\"id\":\"p" + index + "\""));
        }
        plans.append(']');
        assertThatThrownBy(() -> BrowserPlanningJson.save(body(write(plans.toString(), "[]", "0",
                "\"" + MUTATION + "\"")))).isInstanceOf(IllegalArgumentException.class);
        StringBuilder saved = new StringBuilder("[");
        for (int index = 0; index <= 100; index++) saved.append(index == 0 ? "" : ",").append("\"s").append(index).append('"');
        saved.append(']');
        assertThatThrownBy(() -> BrowserPlanningJson.save(body(write("[]", saved.toString(), "0",
                "\"" + MUTATION + "\"")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void deleteAcceptsOnlyAPositiveIntegralExpectedVersion() {
        assertThat(BrowserPlanningJson.delete(body("{\"expectedVersion\":3}"))).isEqualTo(3);
        assertThat(BrowserPlanningJson.delete(body("{\"expectedVersion\":9007199254740991}")))
                .isEqualTo(9_007_199_254_740_991L);
        for (String json : List.of("{}", "{\"expectedVersion\":0}", "{\"expectedVersion\":-1}",
                "{\"expectedVersion\":1.0}", "{\"expectedVersion\":\"1\"}", "{\"expectedVersion\":9007199254740992}",
                "{\"expectedVersion\":1,\"expectedVersion\":1}", "{\"expectedVersion\":1,\"x\":1}",
                "{\"expectedVersion\":1}[]", "[]", "")) {
            assertThatThrownBy(() -> BrowserPlanningJson.delete(body(json))).as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
