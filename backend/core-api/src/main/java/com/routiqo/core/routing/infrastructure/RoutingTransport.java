package com.routiqo.core.routing.infrastructure;

import java.net.URI;

/** Outbound transport for fixed-destination routing and place adapters. */
@FunctionalInterface
public interface RoutingTransport {
    String get(URI uri) throws Exception;
}
