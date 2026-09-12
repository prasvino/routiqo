package com.routiqo.core.routeupdate.domain;

public enum QuickSignalValue {
    QUEUE_UNDER_5(Category.QUEUE),
    QUEUE_5_TO_15(Category.QUEUE),
    QUEUE_15_TO_30(Category.QUEUE),
    QUEUE_OVER_30(Category.QUEUE),

    TRAFFIC_MOVING(Category.TRAFFIC),
    TRAFFIC_SLOW(Category.TRAFFIC),
    TRAFFIC_VERY_SLOW(Category.TRAFFIC),
    TRAFFIC_STOPPED(Category.TRAFFIC),

    PARKING_AVAILABLE(Category.PARKING),
    PARKING_FILLING(Category.PARKING),
    PARKING_FULL(Category.PARKING),

    FOOD_QUEUE_NONE(Category.FOOD_QUEUE),
    FOOD_QUEUE_SHORT(Category.FOOD_QUEUE),
    FOOD_QUEUE_LONG(Category.FOOD_QUEUE),

    RESTROOM_USABLE(Category.RESTROOM),
    RESTROOM_BUSY(Category.RESTROOM),
    RESTROOM_PROBLEM_REPORTED(Category.RESTROOM);

    public enum Category { QUEUE, TRAFFIC, PARKING, FOOD_QUEUE, RESTROOM }

    private final Category category;

    QuickSignalValue(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
