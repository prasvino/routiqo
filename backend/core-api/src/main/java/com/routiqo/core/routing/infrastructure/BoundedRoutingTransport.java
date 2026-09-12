package com.routiqo.core.routing.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded streaming body; only called with URLs built by the fixed-host provider adapter. */
public final class BoundedRoutingTransport implements RoutingTransport, RoutingPostTransport {
    private final int maximumBytes;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public BoundedRoutingTransport() { this(1024 * 1024); }
    public BoundedRoutingTransport(int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > 1024 * 1024) throw new IllegalArgumentException("Invalid response bound");
        this.maximumBytes = maximumBytes;
    }
    @Override public String get(URI uri) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").GET().build();
        var response = execute(request);
        if (response.status() != 200) throw new IOException("Routing provider unavailable");
        return response.body();
    }
    @Override public RoutingPostTransport.Response post(URI uri, String json) throws Exception {
        if (json == null || json.isBlank() || json.length() > 20 * 1024)
            throw new IllegalArgumentException("Invalid routing request body");
        var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(json));
        if (encoded.remaining() > 20 * 1024)
            throw new IllegalArgumentException("Invalid routing request body");
        byte[] body = new byte[encoded.remaining()];
        encoded.get(body);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response = execute(request);
        if (response.status() != 200 && response.status() != 400)
            throw new IOException("Routing provider unavailable");
        return response;
    }
    private RoutingPostTransport.Response execute(HttpRequest request) throws Exception {
        var operation = client.sendAsync(request, info -> new LimitedBody(maximumBytes));
        HttpResponse<byte[]> response;
        try {
            // HttpRequest.timeout alone does not bound a body stalled after its headers.
            response = operation.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            operation.cancel(true);
            throw new IOException("Routing provider timed out");
        } catch (InterruptedException interrupted) {
            operation.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            throw new IOException("Routing provider unavailable");
        }
        return new RoutingPostTransport.Response(response.statusCode(),
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString());
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Routing response exceeds its limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
