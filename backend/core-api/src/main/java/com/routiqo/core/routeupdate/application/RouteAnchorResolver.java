package com.routiqo.core.routeupdate.application;

import com.routiqo.core.routeupdate.domain.ResolvedRouteAnchors;
import com.routiqo.core.routeupdate.domain.RouteAnchor;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Resolves curated relevance from a fresh provider route without retaining geometry.
 * Callers must perform authenticated journey/consent/context authority separately.
 */
public final class RouteAnchorResolver {
    private static final double EARTH_RADIUS_METRES = 6_371_008.8;
    private static final double MATCH_METRES = 100.0;
    private static final double ENDPOINT_EXCLUSION_METRES = 1_000.0;

    private final RouteProvider routes;
    private final RouteAnchorCatalog catalog;

    public RouteAnchorResolver(RouteProvider routes, RouteAnchorCatalog catalog) {
        if (routes == null || routes.identity() != RouteProvider.Identity.VALHALLA || catalog == null) {
            throw conflict();
        }
        this.routes = routes;
        this.catalog = catalog;
    }

    /** Private catalog identity captured before provider work for two-transaction binding. */
    public UUID catalogVersion() {
        return catalog.version();
    }

    public ResolvedRouteAnchors resolve(RouteRequest request, int selectedAlternative) {
        if (request == null || selectedAlternative < 0 || selectedAlternative > 2) throw conflict();
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw conflict();

        List<RouteOption> alternatives = routes.routes(request);
        if (alternatives == null || alternatives.size() > 3) throw conflict();
        try {
            alternatives = List.copyOf(alternatives);
        } catch (RuntimeException invalid) {
            throw conflict();
        }
        if (alternatives.isEmpty()) {
            return new ResolvedRouteAnchors(catalog.version(), ResolvedRouteAnchors.Status.NO_ROUTE,
                    java.util.Map.of());
        }
        if (selectedAlternative >= alternatives.size()) throw conflict();

        RouteOption selected = alternatives.get(selectedAlternative);
        List<RouteRequest.Coordinate> geometry = selected.geometry();
        var matched = new LinkedHashMap<java.util.UUID,
                java.util.Set<com.routiqo.core.routeupdate.domain.QuickSignalValue.Category>>();
        for (RouteAnchor anchor : catalog.anchors()) {
            if (excluded(anchor.location(), request, geometry)) continue;
            boolean onVertex = geometry.stream()
                    .anyMatch(vertex -> distanceMetres(anchor.location(), vertex) <= MATCH_METRES);
            if (onVertex) {
                matched.put(anchor.anchorId(), anchor.categories());
                if (matched.size() > 128) throw conflict();
            }
        }
        return new ResolvedRouteAnchors(catalog.version(), ResolvedRouteAnchors.Status.ROUTE, matched);
    }

    private static boolean excluded(RouteRequest.Coordinate anchor, RouteRequest request,
            List<RouteRequest.Coordinate> geometry) {
        return distanceMetres(anchor, request.origin()) <= ENDPOINT_EXCLUSION_METRES
                || distanceMetres(anchor, request.destination()) <= ENDPOINT_EXCLUSION_METRES
                || distanceMetres(anchor, geometry.getFirst()) <= ENDPOINT_EXCLUSION_METRES
                || distanceMetres(anchor, geometry.getLast()) <= ENDPOINT_EXCLUSION_METRES;
    }

    static double distanceMetres(RouteRequest.Coordinate first, RouteRequest.Coordinate second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        double latitude1 = Math.toRadians(first.latitude());
        double latitude2 = Math.toRadians(second.latitude());
        double latitudeDelta = latitude2 - latitude1;
        double longitudeDelta = Math.toRadians(Math.IEEEremainder(
                second.longitude() - first.longitude(), 360.0));
        double latitudeTerm = Math.sin(latitudeDelta / 2.0);
        double longitudeTerm = Math.sin(longitudeDelta / 2.0);
        double haversine = latitudeTerm * latitudeTerm
                + Math.cos(latitude1) * Math.cos(latitude2) * longitudeTerm * longitudeTerm;
        double clamped = Math.max(0.0, Math.min(1.0, haversine));
        return 2.0 * EARTH_RADIUS_METRES * Math.asin(Math.sqrt(clamped));
    }

    private static RouteAnchorResolutionConflict conflict() {
        return new RouteAnchorResolutionConflict();
    }

    @Override public String toString() { return "RouteAnchorResolver[private]"; }
}
