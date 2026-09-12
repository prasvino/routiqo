package com.routiqo.core.routing;

import com.routiqo.core.routing.infrastructure.BoundedRoutingTransport;
import com.routiqo.core.routing.infrastructure.RoutingPostTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutingPostTransportTest {
    @Test void sendsPrivateJsonBodyAndPreservesOnlySupportedStatuses() throws Exception {
        var calls = new AtomicInteger();
        var followed = new AtomicInteger();
        var method = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var cookie = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/route", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = "{\"error_code\":442}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Location", "/followed");
            exchange.sendResponseHeaders(status.get(), response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.createContext("/followed", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/route");
            var transport = new BoundedRoutingTransport();
            String request = "{\"synthetic\":\"சென்னை\"}";
            assertEquals(200, transport.post(uri, request).status());
            assertEquals("POST", method.get());
            assertEquals(request, body.get());
            assertEquals("application/json", contentType.get());
            assertNull(authorization.get());
            assertNull(cookie.get());
            assertNull(query.get());
            status.set(400);
            var error = transport.post(uri, request);
            assertEquals(400, error.status());
            assertEquals("{\"error_code\":442}", error.body());
            assertEquals("RoutingResponse[private]", error.toString());
            status.set(302);
            assertThrows(IOException.class, () -> transport.post(uri, request));
            status.set(500);
            assertThrows(IOException.class, () -> transport.post(uri, request));
            status.set(200);
            String exactLimit = "\"" + "é".repeat(10239) + "\"";
            assertEquals(20480, exactLimit.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(200, transport.post(uri, exactLimit).status());
            assertEquals(exactLimit, body.get());
            status.set(400);
            assertThrows(IOException.class, () -> new BoundedRoutingTransport(16).post(uri, request));
            assertEquals(6, calls.get());
            assertEquals(0, followed.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsOversizedAndMalformedRequestBytesBeforeSending() {
        var transport = new BoundedRoutingTransport();
        var unreachable = URI.create("http://127.0.0.1:1/route");
        assertThrows(IllegalArgumentException.class, () -> transport.post(unreachable, "x".repeat(20481)));
        assertThrows(IllegalArgumentException.class, () -> transport.post(unreachable, "é".repeat(10241)));
        assertThrows(IllegalArgumentException.class, () -> transport.post(unreachable, " "));
        assertThrows(IOException.class, () -> transport.post(unreachable, "\ud800"));
        assertEquals("RoutingResponse[private]", new RoutingPostTransport.Response(200, "private").toString());
    }
}
