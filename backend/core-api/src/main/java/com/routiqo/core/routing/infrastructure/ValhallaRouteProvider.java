package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.RouteProvider;
import com.routiqo.core.routing.domain.RouteOption;
import com.routiqo.core.routing.domain.RouteRequest;
import com.routiqo.core.routing.domain.RouteStep;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-origin Valhalla adapter. Failures omit the origin, coordinates and provider response. */
public final class ValhallaRouteProvider implements RouteProvider {
    private static final int MAX_ORIGIN_CHARACTERS = 2048;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ROUTES = 3;
    private static final int MAX_POSITIONS = 10_000;
    private static final int MAX_MANEUVERS = 500;
    private static final int MAX_INSTRUCTION_CHARACTERS = 500;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final URI routeUri;
    private final RoutingPostTransport transport;

    public ValhallaRouteProvider(URI origin, RoutingPostTransport transport) {
        if (origin == null || transport == null || origin.toASCIIString().length() > MAX_ORIGIN_CHARACTERS
                || origin.getScheme() == null
                || (!"http".equalsIgnoreCase(origin.getScheme()) && !"https".equalsIgnoreCase(origin.getScheme()))
                || origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()
                    && !"/".equals(origin.getRawPath()))
                || (origin.getPort() != -1 && (origin.getPort() < 1 || origin.getPort() > 65_535)))
            throw new IllegalArgumentException("Routing provider is not configured");
        String fixedOrigin = origin.getScheme().toLowerCase(Locale.ROOT) + "://" + origin.getRawAuthority();
        this.routeUri = URI.create(fixedOrigin + "/route");
        this.transport = transport;
    }

    @Override public Identity identity() { return Identity.VALHALLA; }

    @Override public List<RouteOption> routes(RouteRequest request) {
        if (request == null) throw new IllegalArgumentException("Route request is required");
        try {
            RoutingPostTransport.Response response = transport.post(routeUri, requestJson(request));
            if (response == null) throw malformed();
            byte[] body = strictUtf8(response.body());
            if (response.status() == 400) return noPath(body);
            if (response.status() != 200) throw malformed();
            return successfulRoutes(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Routing request interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Routing is temporarily unavailable");
        }
    }

    private static String requestJson(RouteRequest request) {
        String costing = switch (request.mode()) {
            case DRIVING -> "auto";
            case WALKING -> "pedestrian";
            case CYCLING -> "bicycle";
        };
        return "{\"locations\":[" + location(request.origin()) + "," + location(request.destination())
                + "],\"costing\":\"" + costing + "\",\"units\":\"kilometers\","
                + "\"language\":\"en-US\",\"directions_type\":\"instructions\","
                + "\"format\":\"json\",\"alternates\":2}";
    }

    private static String location(RouteRequest.Coordinate coordinate) {
        return "{\"lat\":" + Double.toString(coordinate.latitude())
                + ",\"lon\":" + Double.toString(coordinate.longitude()) + ",\"type\":\"break\"}";
    }

    private static List<RouteOption> noPath(byte[] body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode errorCode = root == null ? null : root.get("error_code");
        if (root == null || !root.isObject() || errorCode == null || !errorCode.isIntegralNumber()
                || !errorCode.canConvertToInt() || errorCode.intValue() != 442)
            throw malformed();
        return List.of();
    }

    private static List<RouteOption> successfulRoutes(byte[] body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode primary = root == null ? null : root.get("trip");
        if (root == null || !root.isObject() || primary == null || !primary.isObject()) throw malformed();

        var trips = new ArrayList<JsonNode>();
        trips.add(primary);
        JsonNode alternates = root.get("alternates");
        if (alternates != null) {
            if (!alternates.isArray() || alternates.size() > MAX_ROUTES - 1) throw malformed();
            for (JsonNode alternate : alternates) {
                JsonNode trip = alternate == null ? null : alternate.get("trip");
                if (alternate == null || !alternate.isObject() || trip == null || !trip.isObject())
                    throw malformed();
                trips.add(trip);
            }
        }

        var routes = new ArrayList<RouteOption>();
        for (JsonNode trip : trips) routes.add(route(trip));
        return List.copyOf(routes);
    }

    private static RouteOption route(JsonNode trip) {
        JsonNode status = trip.get("status");
        JsonNode locations = trip.get("locations");
        JsonNode legs = trip.get("legs");
        JsonNode summary = trip.get("summary");
        if (status == null || !status.isIntegralNumber() || !status.canConvertToInt() || status.intValue() != 0
                || !"kilometers".equals(text(trip, "units")) || !"en-US".equals(text(trip, "language"))
                || locations == null || !locations.isArray() || locations.size() != 2
                || !locations.get(0).isObject() || !locations.get(1).isObject()
                || legs == null || !legs.isArray() || legs.size() != 1
                || summary == null || !summary.isObject())
            throw malformed();

        double distanceMetres = kilometres(summary, "length");
        double durationSeconds = nonNegativeNumber(summary, "time");
        JsonNode leg = legs.get(0);
        if (leg == null || !leg.isObject()) throw malformed();
        JsonNode legSummary = leg.get("summary");
        if (legSummary == null || !legSummary.isObject()) throw malformed();
        kilometres(legSummary, "length");
        nonNegativeNumber(legSummary, "time");

        JsonNode shape = leg.get("shape");
        JsonNode maneuvers = leg.get("maneuvers");
        if (shape == null || !shape.isTextual() || maneuvers == null || !maneuvers.isArray()
                || maneuvers.isEmpty() || maneuvers.size() > MAX_MANEUVERS)
            throw malformed();
        List<RouteRequest.Coordinate> geometry = decodeShape(shape.textValue());
        List<RouteStep> steps = maneuvers(maneuvers, geometry);
        return new RouteOption(distanceMetres, durationSeconds, geometry, steps);
    }

    private static List<RouteStep> maneuvers(JsonNode maneuvers, List<RouteRequest.Coordinate> geometry) {
        var steps = new ArrayList<RouteStep>();
        int previousEnd = 0;
        for (int i = 0; i < maneuvers.size(); i++) {
            JsonNode maneuver = maneuvers.get(i);
            if (maneuver == null || !maneuver.isObject()) throw malformed();
            int begin = index(maneuver, "begin_shape_index");
            int end = index(maneuver, "end_shape_index");
            if (begin < 0 || end < begin || end >= geometry.size() || (i > 0 && begin < previousEnd))
                throw malformed();
            previousEnd = end;
            String instruction = instruction(maneuver);
            steps.add(new RouteStep(instruction, kilometres(maneuver, "length"),
                    nonNegativeNumber(maneuver, "time"), geometry.get(begin)));
        }
        return List.copyOf(steps);
    }

    private static int index(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw malformed();
        return value.intValue();
    }

    private static String instruction(JsonNode maneuver) {
        String instruction = text(maneuver, "instruction");
        if (instruction.isEmpty() || instruction.length() > MAX_INSTRUCTION_CHARACTERS
                || !instruction.equals(instruction.strip())
                || instruction.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(instruction))
            throw malformed();
        return instruction;
    }

    private static double kilometres(JsonNode object, String field) {
        double kilometres = nonNegativeNumber(object, field);
        double metres = kilometres * 1000.0;
        if (!Double.isFinite(metres)) throw malformed();
        return metres;
    }

    private static double nonNegativeNumber(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber()) throw malformed();
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < 0) throw malformed();
        return number;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw malformed();
        return value.textValue();
    }

    private static List<RouteRequest.Coordinate> decodeShape(String encoded) {
        if (encoded == null || encoded.isEmpty()) throw malformed();
        var coordinates = new ArrayList<RouteRequest.Coordinate>();
        long latitude = 0;
        long longitude = 0;
        int cursor = 0;
        while (cursor < encoded.length()) {
            Decoded latitudeDelta = decodeValue(encoded, cursor);
            cursor = latitudeDelta.next();
            Decoded longitudeDelta = decodeValue(encoded, cursor);
            cursor = longitudeDelta.next();
            try {
                latitude = Math.addExact(latitude, latitudeDelta.value());
                longitude = Math.addExact(longitude, longitudeDelta.value());
            } catch (ArithmeticException overflow) {
                throw malformed();
            }
            if (latitude < -90_000_000L || latitude > 90_000_000L
                    || longitude < -180_000_000L || longitude > 180_000_000L
                    || coordinates.size() >= MAX_POSITIONS)
                throw malformed();
            coordinates.add(new RouteRequest.Coordinate(longitude / 1_000_000.0, latitude / 1_000_000.0));
        }
        if (coordinates.size() < 2) throw malformed();
        return List.copyOf(coordinates);
    }

    private static Decoded decodeValue(String encoded, int cursor) {
        long result = 0;
        int shift = 0;
        for (int count = 0; count < 6; count++) {
            if (cursor >= encoded.length()) throw malformed();
            int character = encoded.charAt(cursor++);
            if (character < 63 || character > 126) throw malformed();
            int value = character - 63;
            result |= (long) (value & 0x1f) << shift;
            if ((value & 0x20) == 0) {
                long decoded = (result & 1) == 0 ? result >> 1 : ~(result >> 1);
                return new Decoded(decoded, cursor);
            }
            shift += 5;
        }
        throw malformed();
    }

    private static byte[] strictUtf8(String raw) throws Exception {
        if (raw == null || raw.length() > MAX_RESPONSE_BYTES) throw malformed();
        var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var buffer = encoder.encode(CharBuffer.wrap(raw));
        if (buffer.remaining() > MAX_RESPONSE_BYTES) throw malformed();
        byte[] encoded = new byte[buffer.remaining()];
        buffer.get(encoded);
        return encoded;
    }

    private record Decoded(long value, int next) {}

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid Valhalla response");
    }
}
