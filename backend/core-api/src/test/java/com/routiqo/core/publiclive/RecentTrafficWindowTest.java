package com.routiqo.core.publiclive;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class RecentTrafficWindowTest {
    @ParameterizedTest
    @ValueSource(strings = {"2026-09-25T00:00:00Z", "2026-09-25T00:30:00Z", "2026-09-25T01:59:59Z",
            "2026-09-25T02:00:00Z", "2026-09-25T12:34:56.789Z", "2026-09-25T23:59:59Z", "2028-03-01T00:10:00Z"})
    void staysRecentAlignedAndWithinOneUtcDay(String value) {
        Instant now = Instant.parse(value);
        Instant window = RecentTrafficWindow.before(now);
        Duration age = Duration.between(window, now);
        assertThat(age).isBetween(Duration.ofHours(2), Duration.ofHours(5));
        assertThat(window.getEpochSecond() % 3600).isZero();
        assertThat(window.getNano()).isZero();
        // Offsets used by the fixtures (up to two hours) stay on the window's UTC day.
        assertThat(window.plus(Duration.ofHours(2)).minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate())
                .isEqualTo(window.atOffset(ZoneOffset.UTC).toLocalDate());
    }
}
