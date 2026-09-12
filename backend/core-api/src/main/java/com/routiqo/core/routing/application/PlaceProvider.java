package com.routiqo.core.routing.application;

import com.routiqo.core.routing.domain.PlaceQuery;
import com.routiqo.core.routing.domain.PlaceResults;

public interface PlaceProvider {
    enum Identity {
        MAPBOX("mapbox"),
        PHOTON("photon");

        private final String wireValue;

        Identity(String wireValue) { this.wireValue = wireValue; }

        public String wireValue() { return wireValue; }
    }

    Identity identity();
    PlaceResults search(PlaceQuery query);
}
