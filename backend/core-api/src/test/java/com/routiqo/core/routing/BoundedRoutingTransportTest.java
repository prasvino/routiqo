package com.routiqo.core.routing;

import com.routiqo.core.routing.infrastructure.BoundedRoutingTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedRoutingTransportTest {
    @Test void interruptionStopsWaitingForAStalledBody() throws Exception {
        var release = new CountDownLatch(1);
        var headersSent = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                headersSent.countDown();
                try { release.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled");
        var caller = Thread.ofVirtual().unstarted(() -> {
            try { new BoundedRoutingTransport(16).get(uri); }
            catch (Exception exception) { failure.set(exception); }
        });
        try {
            caller.start();
            assertTrue(headersSent.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
        } finally { caller.interrupt(); release.countDown(); server.stop(0); caller.join(2000); }
    }

    @Test void wholeRequestDeadlineIncludesAStalledResponseBody() throws Exception {
        var release = new CountDownLatch(1);
        var headersSent = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                headersSent.countDown();
                try { release.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled");
            long started = System.nanoTime();
            var failure = assertThrows(IOException.class, () -> new BoundedRoutingTransport(16).get(uri));
            assertEquals("Routing provider timed out", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, headersSent.getCount());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 17);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void boundsChunkedResponsesAndRejectsMalformedUtf8() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/exact", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write("éééé".getBytes(StandardCharsets.UTF_8)); }
        });
        server.createContext("/overflow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write("ééééx".getBytes(StandardCharsets.UTF_8)); }
        });
        server.createContext("/invalid", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) { output.write(new byte[] {(byte) 0xc3, 0x28}); }
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var transport = new BoundedRoutingTransport(8);
            assertEquals("éééé", transport.get(URI.create(base + "/exact")));
            assertThrows(IOException.class, () -> transport.get(URI.create(base + "/overflow")));
            assertThrows(IOException.class, () -> transport.get(URI.create(base + "/invalid")));
        } finally { server.stop(0); }
    }

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
