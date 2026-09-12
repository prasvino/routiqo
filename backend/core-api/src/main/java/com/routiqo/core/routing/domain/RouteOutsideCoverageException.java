package com.routiqo.core.routing.domain;

public final class RouteOutsideCoverageException extends IllegalArgumentException {
    public RouteOutsideCoverageException() {
        super("Route is outside the configured coverage region");
    }
}
