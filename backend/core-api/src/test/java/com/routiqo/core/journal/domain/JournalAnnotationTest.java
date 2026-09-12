package com.routiqo.core.journal.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JournalAnnotationTest {
    @Test void derivedAnnotationIsEmptyAndUnsaved() {
        assertThat(JournalAnnotation.empty()).isEqualTo(new JournalAnnotation("", "", 0, null));
        assertThatThrownBy(() -> new JournalAnnotation("title", "", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalAnnotation("", "", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void limitsAreUtf16CodeUnitsAndAuthoredTextIsPreserved() {
        String title = " \t".replace("\t", "x") + "a".repeat(118);
        String notes = " first\tline\r\nsecond \n";
        var mutation = new JournalMutation(title, notes, 0, UUID.randomUUID());
        assertThat(mutation.title()).isEqualTo(title);
        assertThat(mutation.notes()).isEqualTo(notes);
        assertThat(new JournalMutation("😀".repeat(60), "n".repeat(4_000), 0, UUID.randomUUID())).isNotNull();
        assertThatThrownBy(() -> new JournalMutation("😀".repeat(61), "", 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalMutation("", "n".repeat(4_001), 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void controlsAndUnpairedSurrogatesAreRejected() {
        for (String title : new String[] {"line\nfeed", "nul\0", "delete\u007f", "c1\u0085", "high\ud800", "low\udc00"})
            assertThatThrownBy(() -> new JournalMutation(title, "", 0, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        for (String notes : new String[] {"nul\0", "backspace\b", "c1\u0085", "high\ud800", "low\udc00"})
            assertThatThrownBy(() -> new JournalMutation("", notes, 0, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void versionsMustStayJavaScriptSafe() {
        assertThat(new JournalMutation("", "", JournalAnnotation.MAX_EXPECTED_VERSION, UUID.randomUUID())).isNotNull();
        assertThatThrownBy(() -> new JournalMutation("", "", -1, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalMutation("", "", JournalAnnotation.MAX_VERSION, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalAnnotation("", "", JournalAnnotation.MAX_VERSION + 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void authoredContentIsRedactedFromDiagnosticStrings() {
        String title = "private title"; String notes = "private notes";
        var annotation = new JournalAnnotation(title, notes, 1, Instant.now());
        var mutation = new JournalMutation(title, notes, 0, UUID.randomUUID());
        assertThat(annotation.toString()).doesNotContain(title, notes);
        assertThat(mutation.toString()).doesNotContain(title, notes);
    }
}
