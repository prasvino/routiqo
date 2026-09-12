package com.routiqo.core.routing.application;

import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.List;

public interface RouteProvider {
    enum Identity {
        MAPBOX("mapbox"),
        VALHALLA("valhalla");

        private final String wireValue;

        Identity(String wireValue) { this.wireValue = wireValue; }

        public String wireValue() { return wireValue; }
    }

    Identity identity();
    List<RouteOption> routes(RouteRequest request);
}
