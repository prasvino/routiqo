package com.routiqo.core.routing.application;

import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.List;

public interface RouteProvider {
    List<RouteOption> routes(RouteRequest request);
}
