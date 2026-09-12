package com.routiqo.core.routing;

import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.domain.PlaceQuery;
import com.routiqo.core.routing.infrastructure.BoundedRoutingTransport;
import com.routiqo.core.routing.infrastructure.PhotonPlaceProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotonPlaceProviderTest {
    private static final String PROPERTIES = """
            "osm_type":"N","osm_id":123,"name":"Marina Beach","housenumber":"10",
            "street":"Beach Road","district":"Marina","city":"Chennai","county":"Chennai",
            "state":"Tamil Nadu","postcode":"600001","country":"India"
            """.replace("\n", "");

    private static String feature(String properties, String coordinates) {
        return "{\"type\":\"Feature\",\"properties\":{" + properties
                + "},\"geometry\":{\"type\":\"Point\",\"coordinates\":" + coordinates + "}}";
    }

    private static String collection(String features) {
        return "{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}";
    }

    @Test void usesOnlyTheFixedPhotonSearchShapeAndNormalizesResults() {
        var requested = new AtomicReference<URI>();
        var calls = new AtomicInteger();
        var provider = new PhotonPlaceProvider(URI.create("https://photon.internal:2322/"), uri -> {
            calls.incrementAndGet();
            requested.set(uri);
            return collection(feature(PROPERTIES, "[80.2824,13.0499]"));
        });

        assertThat(provider.identity()).isEqualTo(PlaceProvider.Identity.PHOTON);
        assertThat(calls).hasValue(0);
        var result = provider.search(new PlaceQuery("  Chennai & beach?limit=99  "));

        assertThat(requested.get().getScheme()).isEqualTo("https");
        assertThat(requested.get().getHost()).isEqualTo("photon.internal");
        assertThat(requested.get().getPort()).isEqualTo(2322);
        assertThat(requested.get().getPath()).isEqualTo("/api");
        assertThat(requested.get().getRawQuery())
                .isEqualTo("q=Chennai+%26+beach%3Flimit%3D99&limit=5&lang=en")
                .doesNotContain("lat=", "lon=", "key=", "token=");
        assertThat(result.attribution()).isEqualTo(PhotonPlaceProvider.ATTRIBUTION);
        assertThat(result.places()).singleElement().satisfies(place -> {
            assertThat(place.id()).isEqualTo("photon:n:123");
            assertThat(place.label()).isEqualTo(
                    "Marina Beach, 10 Beach Road, Marina, Chennai, Tamil Nadu, 600001, India");
            assertThat(place.coordinate().longitude()).isEqualTo(80.2824);
            assertThat(place.coordinate().latitude()).isEqualTo(13.0499);
        });
    }

    @Test void acceptsNamelessAddressesAndDeduplicatesLabelParts() {
        String address = """
                "osm_type":"W","osm_id":456,"housenumber":"4","street":"Anna Salai",
                "district":"Chennai","city":"Chennai","county":"Chennai","state":"Tamil Nadu",
                "postcode":"600002","country":"India"
                """.replace("\n", "");
        var provider = new PhotonPlaceProvider(URI.create("http://127.0.0.1:2322"),
                uri -> collection(feature(address, "[80.267,13.061]")));

        assertThat(provider.search(new PlaceQuery("Anna Salai")).places()).singleElement().satisfies(place -> {
            assertThat(place.id()).isEqualTo("photon:w:456");
            assertThat(place.label()).isEqualTo("4 Anna Salai, Chennai, Tamil Nadu, 600002, India");
        });
        assertThat(new PhotonPlaceProvider(URI.create("http://127.0.0.1:2322"),
                uri -> collection("")).search(new PlaceQuery("No matches")).places()).isEmpty();

        String unicode = "\"osm_type\":\"R\",\"osm_id\":457,\"name\":\"மெரினா 🏖️\","
                + "\"city\":\"சென்னை\",\"country\":\"இந்தியா\"";
        var unicodeProvider = new PhotonPlaceProvider(URI.create("http://127.0.0.1:2322"),
                uri -> collection(feature(unicode, "[80.2824,13.0499]")));
        assertThat(unicodeProvider.search(new PlaceQuery("சென்னை கடற்கரை")).places())
                .singleElement().satisfies(place -> {
                    assertThat(place.id()).isEqualTo("photon:r:457");
                    assertThat(place.label()).isEqualTo("மெரினா 🏖️, சென்னை, இந்தியா");
                });
    }

    @Test void rejectsUnsafeOrAmbiguousOriginsWithoutContactingTransport() {
        var calls = new AtomicInteger();
        List<String> origins = List.of(
                "relative", "ftp://photon.internal", "https://user@photon.internal",
                "https://photon.internal/api", "https://photon.internal?x=1",
                "https://photon.internal#fragment", "https://photon.internal:0",
                "https://photon.internal:65536");
        for (String origin : origins) {
            assertThatThrownBy(() -> new PhotonPlaceProvider(URI.create(origin), uri -> {
                calls.incrementAndGet();
                return collection("");
            })).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Place provider is not configured")
                    .hasNoCause();
        }
        String oversized = "https://" + "a".repeat(2048) + ".internal";
        assertThatThrownBy(() -> new PhotonPlaceProvider(URI.create(oversized), uri -> collection("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void rejectsMalformedJsonShapesIdentitiesCoordinatesAndFields() {
        String valid = feature(PROPERTIES, "[80.2824,13.0499]");
        String duplicate = feature(PROPERTIES, "[80.2824,13.0499]")
                + "," + feature(PROPERTIES, "[80.3,13.1]");
        List<String> responses = List.of(
                "{}", collection(valid) + "{}",
                "{\"type\":\"FeatureCollection\",\"type\":\"FeatureCollection\",\"features\":[]}",
                "{\"type\":\"Feature\",\"features\":[]}",
                collection(String.join(",", java.util.Collections.nCopies(6, valid))),
                collection(duplicate),
                collection(feature(PROPERTIES.replace("\"N\"", "\"X\""), "[80,13]")),
                collection(feature(PROPERTIES.replace("123", "0"), "[80,13]")),
                collection(feature(PROPERTIES.replace("123", "1.0"), "[80,13]")),
                collection(feature(PROPERTIES.replace("123", "9223372036854775808"), "[80,13]")),
                collection(valid.replace("\"type\":\"Feature\"", "\"type\":\"Other\"")),
                collection(valid.replace("\"type\":\"Point\"", "\"type\":\"LineString\"")),
                collection(feature(PROPERTIES, "[80]")),
                collection(feature(PROPERTIES, "[80,91]")),
                collection(feature(PROPERTIES.replace("\"Marina Beach\"", "7"), "[80,13]")),
                collection(feature(PROPERTIES.replace("\"Marina Beach\"", "\"\\tMarina Beach\""), "[80,13]")),
                collection(feature(PROPERTIES.replace("\"Marina Beach\"", "\"Marina Beach\\n\""), "[80,13]")),
                collection(feature(PROPERTIES.replace("\"Marina Beach\"", "\"bad\\nlabel\""), "[80,13]")),
                collection(feature("\"osm_type\":\"R\",\"osm_id\":789", "[80,13]")));

        for (String response : responses) assertUnavailable(response);
    }

    @Test void enforcesUtf8AndLabelBoundsBeforeReturningProviderData() {
        assertUnavailable("\"" + "é".repeat(131_073) + "\"");
        assertUnavailable(collection(feature(
                "\"osm_type\":\"N\",\"osm_id\":1,\"name\":\"" + "x".repeat(513) + "\"", "[80,13]")));
        String hundred = "x".repeat(100);
        assertUnavailable(collection(feature(
                "\"osm_type\":\"N\",\"osm_id\":1,\"name\":\"a" + hundred + "\","
                + "\"street\":\"b" + hundred + "\",\"district\":\"c" + hundred + "\","
                + "\"city\":\"d" + hundred + "\",\"county\":\"e" + hundred + "\","
                + "\"state\":\"f" + hundred + "\"", "[80,13]")));
        assertUnavailable(collection(feature(
                "\"osm_type\":\"N\",\"osm_id\":1,\"name\":\"\\ud800\"", "[80,13]")));
    }

    @Test void redactsProviderFailuresAndPreservesInterruption() {
        var provider = new PhotonPlaceProvider(URI.create("https://private.internal"), uri -> {
            throw new Exception("private failure " + uri);
        });
        assertThatThrownBy(() -> provider.search(new PlaceQuery("Private home")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Place search is temporarily unavailable")
                .hasNoCause()
                .hasToString("java.lang.IllegalStateException: Place search is temporarily unavailable");

        var interrupted = new PhotonPlaceProvider(URI.create("https://private.internal"), uri -> {
            throw new InterruptedException("private interruption " + uri);
        });
        try {
            assertThatThrownBy(() -> interrupted.search(new PlaceQuery("Private home")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Place search interrupted")
                    .hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void worksThroughBoundedTransportAndDoesNotFollowRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var followed = new AtomicInteger();
        server.createContext("/api", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && query.startsWith("q=redirect")) {
                exchange.getResponseHeaders().add("Location", "/followed");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = collection(feature(PROPERTIES, "[80.2824,13.0499]"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/followed", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var provider = new PhotonPlaceProvider(origin, new BoundedRoutingTransport(262_144));
            assertThat(provider.search(new PlaceQuery("Marina Beach")).places()).hasSize(1);
            assertThatThrownBy(() -> provider.search(new PlaceQuery("redirect")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Place search is temporarily unavailable")
                    .hasNoCause();
            assertThat(followed).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private static void assertUnavailable(String response) {
        var provider = new PhotonPlaceProvider(URI.create("https://photon.internal"), uri -> response);
        assertThatThrownBy(() -> provider.search(new PlaceQuery("Chennai")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Place search is temporarily unavailable")
                .hasNoCause();
    }
}
