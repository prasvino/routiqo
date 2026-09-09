package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.RouteProvider;
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
}
