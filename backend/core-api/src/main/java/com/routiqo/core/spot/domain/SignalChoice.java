package com.routiqo.core.spot.domain;

/**
 * The one-tap signal values per category (POSTS_AND_SIGNALS_SPEC, Signals). The wire value is the
 * lowercase key within its category; queue values are minutes in four bands.
 */
public enum SignalChoice {
    TRAFFIC_MOVING(SpotCategory.TRAFFIC, "moving"),
    TRAFFIC_SLOW(SpotCategory.TRAFFIC, "slow"),
    TRAFFIC_STOPPED(SpotCategory.TRAFFIC, "stopped"),
    QUEUE_UNDER_5(SpotCategory.QUEUE, "under_5"),
    QUEUE_5_TO_15(SpotCategory.QUEUE, "5_to_15"),
    QUEUE_15_TO_30(SpotCategory.QUEUE, "15_to_30"),
    QUEUE_OVER_30(SpotCategory.QUEUE, "over_30"),
    FOOD_GOOD(SpotCategory.FOOD, "good"),
    FOOD_AVOID(SpotCategory.FOOD, "avoid"),
    FUEL_AVAILABLE(SpotCategory.FUEL, "available"),
    FUEL_LONG_QUEUE(SpotCategory.FUEL, "long_queue"),
    FUEL_NONE(SpotCategory.FUEL, "none"),
    RESTROOM_USABLE(SpotCategory.RESTROOM, "usable"),
    RESTROOM_BUSY(SpotCategory.RESTROOM, "busy"),
    RESTROOM_AVOID(SpotCategory.RESTROOM, "avoid");

    private final SpotCategory category;
    private final String key;

    SignalChoice(SpotCategory category, String key) {
        this.category = category;
        this.key = key;
    }

    public SpotCategory category() { return category; }

    public String key() { return key; }

    public static SignalChoice of(SpotCategory category, String key) {
        for (SignalChoice choice : values())
            if (choice.category == category && choice.key.equals(key)) return choice;
        throw new IllegalArgumentException("Unknown signal value");
    }
}
