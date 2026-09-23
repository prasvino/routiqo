package com.routiqo.core.routeupdate.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Bounded, fixed-host NDMA CAP reader. Its cache is an efficiency aid, never an authority source. */
public final class NdmaCapAlerts {
    private static final URI FEED = URI.create(
            "https://sachet.ndma.gov.in/cap_public_website/rss/rss_india.xml");
    private static final String DETAIL_PREFIX =
            "https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier=";
    private static final Pattern DETAIL_ID = Pattern.compile("^" + Pattern.quote(DETAIL_PREFIX)
            + "([0-9]{8,24})$");
    private static final Pattern PILOT_AREA = Pattern.compile(
            "(?i)\\b(chennai|chengalpattu|chengalpet|kancheepuram|kanchipuram|tiruvallur|thiruvallur)\\b");
    private static final Pattern PILOT_TITLE = Pattern.compile(
            "(?i)\\b(chennai|chengalpattu|chengalpet|kancheepuram|kanchipuram|tiruvallur|thiruvallur|tamil nadu)\\b");
    private static final int FEED_BYTES = 256 * 1024;
    private static final int CAP_BYTES = 64 * 1024;
    private static final int MAX_FEED_ITEMS = 120;
    private static final int MAX_CANDIDATES = 16;
    private static final Duration FEED_CACHE = Duration.ofMinutes(1);
    private static final Duration MAX_ISSUE_AGE = Duration.ofHours(24);

    public record Alert(String id, String event, String area, String severity, String issuer,
            String sourceUrl, Instant issuedAt, Instant expiresAt) {
        public Alert {
            if (id == null || event == null || area == null || severity == null || issuer == null
                    || sourceUrl == null || issuedAt == null || expiresAt == null) {
                throw new IllegalArgumentException("Invalid provider alert");
            }
        }
    }

    public record FetchResult(int status, byte[] body, String etag) {}

    @FunctionalInterface
    public interface Fetcher {
        FetchResult get(URI uri, String etag, int maximumBytes) throws Exception;
    }

    private final Fetcher fetcher;
    private final Clock clock;
    private Instant feedFetchedAt = Instant.MIN;
    private List<FeedItem> feedItems = List.of();
    private final Map<String, CachedCap> capCache = new HashMap<>();

    public NdmaCapAlerts(Fetcher fetcher, Clock clock) {
        this.fetcher = Objects.requireNonNull(fetcher);
        this.clock = Objects.requireNonNull(clock);
    }

    public static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, etag, maximumBytes) -> {
            if (!"https".equals(uri.getScheme())
                    || !"sachet.ndma.gov.in".equals(uri.getHost())
                    || !(uri.equals(FEED) || DETAIL_ID.matcher(uri.toString()).matches())) {
                throw new SecurityException("Provider URL denied");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(4)).header("Accept", "application/xml, text/xml")
                    .header("User-Agent", "Routiqo-Provider-Live/1.0").GET();
            if (etag != null && etag.length() <= 200 && !etag.contains("\r")
                    && !etag.contains("\n")) request.header("If-None-Match", etag);
            HttpResponse<InputStream> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 304) return new FetchResult(304, new byte[0], etag);
                if (response.statusCode() != 200) throw new IllegalStateException("Provider unavailable");
                byte[] bytes = body.readNBytes(maximumBytes + 1);
                if (bytes.length > maximumBytes) throw new IllegalStateException("Provider response too large");
                return new FetchResult(200, bytes,
                        response.headers().firstValue("ETag").orElse(null));
            }
        };
    }

    public synchronized List<Alert> current() {
        Instant now = clock.instant();
        try {
            if (feedFetchedAt.equals(Instant.MIN) || !now.isBefore(feedFetchedAt.plus(FEED_CACHE))) {
                FetchResult response = fetcher.get(FEED, null, FEED_BYTES);
                if (response.status() != 200 || response.body() == null
                        || response.body().length > FEED_BYTES) throw unavailable();
                feedItems = readFeed(response.body());
                feedFetchedAt = now;
            }
            List<Alert> alerts = new ArrayList<>();
            int candidates = 0;
            for (FeedItem item : feedItems) {
                if (!PILOT_TITLE.matcher(item.title()).find()
                        || item.issuedAt().isAfter(now.plusSeconds(60))
                        || !item.issuedAt().isAfter(now.minus(MAX_ISSUE_AGE))) continue;
                if (++candidates > MAX_CANDIDATES) break;
                Alert alert = readCap(item.id(), now);
                if (alert != null) alerts.add(alert);
            }
            return alerts.stream().sorted(Comparator.comparing(Alert::issuedAt).reversed())
                    .limit(10).toList();
        } catch (Exception unavailable) {
            feedFetchedAt = Instant.MIN;
            throw new IllegalStateException("Provider alerts unavailable");
        }
    }

    private Alert readCap(String id, Instant now) throws Exception {
        CachedCap cached = capCache.get(id);
        FetchResult response = fetcher.get(URI.create(DETAIL_PREFIX + id),
                cached == null ? null : cached.etag(), CAP_BYTES);
        byte[] xml;
        if (response.status() == 304 && cached != null) xml = cached.xml();
        else if (response.status() == 200 && response.body() != null
                && response.body().length <= CAP_BYTES) {
            xml = response.body();
            if (capCache.size() >= 128) capCache.clear();
            capCache.put(id, new CachedCap(xml, response.etag()));
        } else throw unavailable();
        Document cap = document(xml);
        Element root = cap.getDocumentElement();
        if (!"alert".equals(root.getLocalName())
                || !"urn:oasis:names:tc:emergency:cap:1.2".equals(root.getNamespaceURI())
                || !"Actual".equals(child(root, "status"))
                || !"Public".equals(child(root, "scope"))
                || !("Alert".equals(child(root, "msgType"))
                        || "Update".equals(child(root, "msgType")))) return null;
        Element info = first(root, "info");
        if (info == null || !child(info, "language").toLowerCase(Locale.ROOT).startsWith("en")
                || !"Met".equals(child(info, "category"))) return null;
        Instant issued = ZonedDateTime.parse(child(root, "sent")).toInstant();
        Instant effective = ZonedDateTime.parse(child(info, "effective")).toInstant();
        Instant expires = ZonedDateTime.parse(child(info, "expires")).toInstant();
        if (issued.isAfter(now.plusSeconds(60)) || !issued.isAfter(now.minus(MAX_ISSUE_AGE))
                || effective.isAfter(now) || !expires.isAfter(now)
                || expires.isAfter(issued.plus(Duration.ofDays(3)))) return null;
        String event = bounded(child(info, "event"), 120);
        String issuer = bounded(child(root, "sender"), 80);
        String severity = child(info, "severity");
        if (event.isBlank() || issuer.isBlank()
                || !List.of("Minor", "Moderate", "Severe", "Extreme").contains(severity)) return null;
        for (Element area : children(info, "area")) {
            String areaName = bounded(child(area, "areaDesc"), 140);
            if (PILOT_AREA.matcher(areaName).find()
                    || "Tamil Nadu".equalsIgnoreCase(areaName)) {
                return new Alert(id, event, areaName, severity, issuer,
                        DETAIL_PREFIX + id, issued, expires);
            }
        }
        return null;
    }

    private static List<FeedItem> readFeed(byte[] xml) throws Exception {
        Document feed = document(xml);
        if (!"rss".equals(feed.getDocumentElement().getNodeName())) throw unavailable();
        NodeList nodes = feed.getElementsByTagName("item");
        if (nodes.getLength() > MAX_FEED_ITEMS) throw unavailable();
        List<FeedItem> items = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element item = (Element) nodes.item(index);
            String link = child(item, "link");
            Matcher match = DETAIL_ID.matcher(link);
            if (!match.matches()) continue;
            try {
                items.add(new FeedItem(match.group(1), bounded(child(item, "title"), 500),
                        ZonedDateTime.parse(child(item, "pubDate"),
                                DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
            } catch (RuntimeException invalid) {
                // A malformed item cannot authorize a provider alert.
            }
        }
        return List.copyOf(items);
    }

    private static Document document(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String child(Element parent, String localName) {
        Element found = first(parent, localName);
        return found == null ? "" : found.getTextContent().trim();
    }

    private static Element first(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName()))
                return element;
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName()))
                found.add(element);
        }
        return found;
    }

    private static String bounded(String value, int limit) {
        String clean = value.replaceAll("[\\p{Cntrl}<>]", " ").trim();
        return clean.length() <= limit ? clean : "";
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Provider alerts unavailable");
    }

    private record FeedItem(String id, String title, Instant issuedAt) {}
    private record CachedCap(byte[] xml, String etag) {}
}
