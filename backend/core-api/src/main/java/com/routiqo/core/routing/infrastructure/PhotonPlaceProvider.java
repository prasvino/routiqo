package com.routiqo.core.routing.infrastructure;

import com.routiqo.core.routing.application.PlaceProvider;
import com.routiqo.core.routing.domain.PlaceMatch;
import com.routiqo.core.routing.domain.PlaceQuery;
import com.routiqo.core.routing.domain.PlaceResults;
import com.routiqo.core.routing.domain.RouteRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-origin Photon adapter. Failures omit the origin, query and provider response. */
public final class PhotonPlaceProvider implements PlaceProvider {
    public static final String ATTRIBUTION =
            "Data © OpenStreetMap contributors, ODbL 1.0. https://osm.org/copyright";
    private static final int MAX_ORIGIN_CHARACTERS = 2048;
    private static final int MAX_RESPONSE_BYTES = 262_144;
    private static final int MAX_LABEL_CHARACTERS = 512;
    private static final List<String> LOCALITY_FIELDS =
            List.of("district", "city", "county", "state", "postcode", "country");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String origin;
    private final RoutingTransport transport;

    public PhotonPlaceProvider(URI origin, RoutingTransport transport) {
        if (origin == null || transport == null || origin.toASCIIString().length() > MAX_ORIGIN_CHARACTERS
                || origin.getScheme() == null
                || (!"http".equalsIgnoreCase(origin.getScheme()) && !"https".equalsIgnoreCase(origin.getScheme()))
                || origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()
                    && !"/".equals(origin.getRawPath()))
                || (origin.getPort() != -1 && (origin.getPort() < 1 || origin.getPort() > 65_535)))
            throw new IllegalArgumentException("Place provider is not configured");
        this.origin = origin.getScheme().toLowerCase(Locale.ROOT) + "://" + origin.getRawAuthority();
        this.transport = transport;
    }

    @Override public Identity identity() { return Identity.PHOTON; }

    @Override public PlaceResults search(PlaceQuery query) {
        if (query == null) throw new IllegalArgumentException("Place query is required");
        try {
            URI uri = URI.create(origin + "/api?q=" + URLEncoder.encode(query.text(), StandardCharsets.UTF_8)
                    + "&limit=5&lang=en");
            String raw = transport.get(uri);
            byte[] encoded = strictUtf8(raw);
            JsonNode root = MAPPER.readTree(encoded);
            JsonNode features = root == null ? null : root.get("features");
            if (root == null || !root.isObject() || !"FeatureCollection".equals(text(root, "type"))
                    || features == null || !features.isArray() || features.size() > 5)
                throw malformed();

            var matches = new ArrayList<PlaceMatch>();
            var identities = new LinkedHashSet<String>();
            for (JsonNode feature : features) {
                PlaceMatch match = match(feature);
                if (!identities.add(match.id())) throw malformed();
                matches.add(match);
            }
            return new PlaceResults(matches, ATTRIBUTION);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Place search interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Place search is temporarily unavailable");
        }
    }

    private static PlaceMatch match(JsonNode feature) {
        if (feature == null || !feature.isObject() || !"Feature".equals(text(feature, "type")))
            throw malformed();
        JsonNode properties = feature.get("properties");
        JsonNode geometry = feature.get("geometry");
        JsonNode coordinates = geometry == null ? null : geometry.get("coordinates");
        if (properties == null || !properties.isObject() || geometry == null || !geometry.isObject()
                || !"Point".equals(text(geometry, "type")) || coordinates == null || !coordinates.isArray()
                || coordinates.size() != 2 || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber())
            throw malformed();

        String osmType = text(properties, "osm_type");
        String type = switch (osmType) {
            case "N", "W", "R" -> osmType.toLowerCase(Locale.ROOT);
            default -> throw malformed();
        };
        JsonNode osmIdNode = properties.get("osm_id");
        if (osmIdNode == null || !osmIdNode.isIntegralNumber() || !osmIdNode.canConvertToLong())
            throw malformed();
        long osmId = osmIdNode.longValue();
        if (osmId <= 0) throw malformed();

        String label = label(properties);
        return new PlaceMatch("photon:" + type + ":" + osmId, label,
                new RouteRequest.Coordinate(coordinates.get(0).doubleValue(), coordinates.get(1).doubleValue()));
    }

    private static String label(JsonNode properties) {
        var parts = new LinkedHashSet<String>();
        add(parts, optionalText(properties, "name"));

        String houseNumber = optionalText(properties, "housenumber");
        String street = optionalText(properties, "street");
        if (houseNumber != null && street != null) add(parts, houseNumber + " " + street);
        else if (street != null) add(parts, street);
        else add(parts, houseNumber);

        for (String field : LOCALITY_FIELDS) add(parts, optionalText(properties, field));
        String label = String.join(", ", parts);
        if (label.isEmpty() || label.length() > MAX_LABEL_CHARACTERS) throw malformed();
        return label;
    }

    private static void add(Set<String> parts, String value) {
        if (value != null) parts.add(value);
    }

    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) return null;
        if (!value.isTextual()) throw malformed();
        String supplied = value.textValue();
        if (supplied.length() > MAX_LABEL_CHARACTERS
                || supplied.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(supplied))
            throw malformed();
        String text = supplied.strip();
        if (text.isEmpty()) throw malformed();
        return text;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw malformed();
        return value.textValue();
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

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Invalid Photon response");
    }
}
