package com.routiqo.core.routing.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounded streaming body; only called with URLs built by the fixed-host provider adapter. */
public final class BoundedRoutingTransport implements MapboxRouteProvider.Transport {
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
        var response = client.send(request, info -> new LimitedBody(maximumBytes));
        if (response.statusCode() != 200) throw new IOException("Routing provider unavailable");
        return new String(response.body(), StandardCharsets.UTF_8);
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
