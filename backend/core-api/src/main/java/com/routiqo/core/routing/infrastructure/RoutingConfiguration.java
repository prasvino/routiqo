package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.application.PlaceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing")
public class RoutingConfiguration {
    @Bean RouteProvider routeProvider(@Value("${ROUTIQO_MAPBOX_TOKEN}") String token) {
        return new MapboxRouteProvider(token, new BoundedRoutingTransport());
    }
    @Bean PlaceProvider placeProvider(@Value("${ROUTIQO_MAPBOX_TOKEN}") String token) {
        return new MapboxPlaceProvider(token, new BoundedRoutingTransport(262144));
    }
}
