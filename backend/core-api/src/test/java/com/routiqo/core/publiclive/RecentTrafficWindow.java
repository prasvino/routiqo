package com.routiqo.core.publiclive;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Fixture window for tests that combine fixed application clocks with real database time (grant lifetimes,
 * candidate retention, status expiry). A hard-coded date stops working once real time moves past those
 * 24-hour limits, so the window is derived from the current time instead.
 *
 * <p>The result is whole-hour aligned (so every 5-minute window boundary holds), two to five hours in the
 * past (so small offsets are already in the past for the database), and at most 21:00 UTC (so the window
 * and the following two hours of offsets fall on the same UTC day for daily budgets).
 */
public final class RecentTrafficWindow {
    private static final Duration AGE = Duration.ofHours(2);
    private static final int LATEST_START_HOUR = 21;

    private RecentTrafficWindow() {}

    public static Instant now() {
        return before(Clock.systemUTC().instant());
    }

    static Instant before(Instant now) {
        Instant window = now.truncatedTo(ChronoUnit.HOURS).minus(AGE);
        int hour = window.atOffset(ZoneOffset.UTC).getHour();
        return hour > LATEST_START_HOUR ? window.minus(Duration.ofHours(hour - LATEST_START_HOUR)) : window;
    }
}
