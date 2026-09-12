package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.application.RegionLimitedRouteProvider;
import com.routiqo.core.routing.domain.RoutingRegion;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing")
public class RoutingConfiguration {
    private static final String CONFIGURATION_ERROR = "Routing providers are not configured";

    @Bean Providers routingProviders(Environment environment) {
        try {
            URI valhallaOrigin = URI.create(required(environment, "ROUTIQO_VALHALLA_ORIGIN"));
            URI photonOrigin = URI.create(required(environment, "ROUTIQO_PHOTON_ORIGIN"));
            var region = new RoutingRegion(
                    coordinate(environment, "ROUTIQO_ROUTING_REGION_WEST"),
                    coordinate(environment, "ROUTIQO_ROUTING_REGION_SOUTH"),
                    coordinate(environment, "ROUTIQO_ROUTING_REGION_EAST"),
                    coordinate(environment, "ROUTIQO_ROUTING_REGION_NORTH"));
            RouteProvider routes = new RegionLimitedRouteProvider(
                    new ValhallaRouteProvider(valhallaOrigin, new BoundedRoutingTransport()), region);
            PlaceProvider places = new PhotonPlaceProvider(
                    photonOrigin, new BoundedRoutingTransport(262_144));
            return new Providers(routes, places);
        } catch (RuntimeException invalidConfiguration) {
            throw misconfigured();
        }
    }

    @Bean RouteProvider routeProvider(Providers providers) { return providers.routes(); }

    @Bean PlaceProvider placeProvider(Providers providers) { return providers.places(); }

    private static String required(Environment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw misconfigured();
        return value;
    }

    private static double coordinate(Environment environment, String property) {
        String value = required(environment, property);
        if (value.length() > 64) throw misconfigured();
        return Double.parseDouble(value);
    }

    private static IllegalStateException misconfigured() {
        return new IllegalStateException(CONFIGURATION_ERROR);
    }

    record Providers(RouteProvider routes, PlaceProvider places) {}
}
