package com.routiqo.core.routing.infrastructure;

import java.net.URI;

/** Bounded JSON POST to an adapter-owned destination, without browser credentials. */
@FunctionalInterface
public interface RoutingPostTransport {
    Response post(URI uri, String json) throws Exception;

    record Response(int status, String body) {
        @Override public String toString() { return "RoutingResponse[private]"; }
    }
}
