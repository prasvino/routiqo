package com.routiqo.core.routing.application;

import com.routiqo.core.routing.domain.PlaceQuery;
import com.routiqo.core.routing.domain.PlaceResults;

public interface PlaceProvider {
    PlaceResults search(PlaceQuery query);
}
