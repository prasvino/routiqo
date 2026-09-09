package com.routiqo.core.routing;

import com.routiqo.core.routing.infrastructure.BoundedRoutingTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedRoutingTransportTest {
    @Test void boundsBodiesAndRejectsRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/small", exchange -> {
            var body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/large", exchange -> {
            var body = new byte[1024];
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/small");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var transport = new BoundedRoutingTransport(16);
            assertEquals("{}", transport.get(URI.create(base + "/small")));
            assertThrows(IOException.class, () -> transport.get(URI.create(base + "/large")));
            assertThrows(IOException.class, () -> transport.get(URI.create(base + "/redirect")));
        } finally { server.stop(0); }
    }
}
