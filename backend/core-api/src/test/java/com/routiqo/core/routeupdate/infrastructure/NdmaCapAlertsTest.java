package com.routiqo.core.routeupdate.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NdmaCapAlertsTest {
    private static final Instant NOW = Instant.parse("2026-09-23T06:00:00Z");
    private static final String DETAIL =
            "https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier=123456789012";

    @Test void emitsOnlyCurrentAttributedChennaiAreaAlerts() {
        FakeFetcher fetch = new FakeFetcher(feed(), cap("Actual", "Alert", "Chennai district",
                "2026-09-23T14:00:00+05:30"));
        NdmaCapAlerts alerts = new NdmaCapAlerts(fetch, clock());
        var current = alerts.current();
        assertThat(current).hasSize(1);
        assertThat(current.getFirst().event()).isEqualTo("Heavy rain");
        assertThat(current.getFirst().area()).isEqualTo("Chennai district");
        assertThat(current.getFirst().issuer()).isEqualTo("IMD Chennai");
        assertThat(current.getFirst().sourceUrl()).isEqualTo(DETAIL);
    }

    @Test void rejectsWrongAreaCancelledExpiredAndNonActual() {
        for (String cap : List.of(
                cap("Actual", "Alert", "Madurai districts of Tamil Nadu",
                        "2026-09-23T14:00:00+05:30"),
                cap("Actual", "Cancel", "Chennai district", "2026-09-23T14:00:00+05:30"),
                cap("Test", "Alert", "Chennai district", "2026-09-23T14:00:00+05:30"),
                cap("Actual", "Alert", "Chennai district", "2026-09-23T10:00:00+05:30"))) {
            assertThat(new NdmaCapAlerts(new FakeFetcher(feed(), cap), clock()).current()).isEmpty();
        }
    }

    @Test void conditionalCapRequestUsesEtagAndCachedBodyOn304() {
        FakeFetcher fetch = new FakeFetcher(feed(), cap("Actual", "Alert", "Chennai district",
                "2026-09-23T14:00:00+05:30"));
        NdmaCapAlerts alerts = new NdmaCapAlerts(fetch, clock());
        assertThat(alerts.current()).hasSize(1);
        assertThat(alerts.current()).hasSize(1);
        assertThat(fetch.detailEtags).containsExactly(null, "\"cap-version-1\"");
        assertThat(fetch.feedCalls).isEqualTo(1);
    }

    @Test void rejectsDtdAndOutageWithoutReturningCachedAlerts() {
        FakeFetcher fetch = new FakeFetcher(feed(), cap("Actual", "Alert", "Chennai district",
                "2026-09-23T14:00:00+05:30"));
        NdmaCapAlerts alerts = new NdmaCapAlerts(fetch, clock());
        assertThat(alerts.current()).hasSize(1);
        fetch.outage = true;
        assertThatThrownBy(alerts::current).isInstanceOf(IllegalStateException.class);

        String malicious = "<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + feed().replace("Chennai", "&xxe;");
        assertThatThrownBy(() -> new NdmaCapAlerts(new FakeFetcher(malicious,
                cap("Actual", "Alert", "Chennai district", "2026-09-23T14:00:00+05:30")),
                clock()).current()).isInstanceOf(IllegalStateException.class);
    }

    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private static String feed() {
        return """
                <rss version="2.0"><channel><item>
                  <title>Heavy rain warning for Chennai</title>
                  <link>https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier=123456789012</link>
                  <pubDate>Wed, 23 Sep 2026 05:30:00 GMT</pubDate>
                </item></channel></rss>
                """;
    }

    private static String cap(String status, String type, String area, String expires) {
        return """
                <cap:alert xmlns:cap="urn:oasis:names:tc:emergency:cap:1.2">
                  <cap:sender>IMD Chennai</cap:sender>
                  <cap:sent>2026-09-23T11:00:00+05:30</cap:sent>
                  <cap:status>%s</cap:status><cap:msgType>%s</cap:msgType>
                  <cap:scope>Public</cap:scope><cap:info>
                    <cap:language>en-IN</cap:language><cap:category>Met</cap:category>
                    <cap:event>Heavy rain</cap:event><cap:severity>Severe</cap:severity>
                    <cap:effective>2026-09-23T11:00:00+05:30</cap:effective>
                    <cap:expires>%s</cap:expires>
                    <cap:area><cap:areaDesc>%s</cap:areaDesc></cap:area>
                  </cap:info>
                </cap:alert>
                """.formatted(status, type, expires, area);
    }

    private static final class FakeFetcher implements NdmaCapAlerts.Fetcher {
        private final String feed;
        private final String cap;
        private final List<String> detailEtags = new ArrayList<>();
        private int feedCalls;
        private boolean outage;

        private FakeFetcher(String feed, String cap) {
            this.feed = feed;
            this.cap = cap;
        }

        @Override public NdmaCapAlerts.FetchResult get(URI uri, String etag, int limit) {
            if (outage) throw new IllegalStateException("offline");
            if (uri.toString().endsWith("rss_india.xml")) {
                feedCalls++;
                return new NdmaCapAlerts.FetchResult(200,
                        feed.getBytes(StandardCharsets.UTF_8), null);
            }
            detailEtags.add(etag);
            return etag == null
                    ? new NdmaCapAlerts.FetchResult(200,
                            cap.getBytes(StandardCharsets.UTF_8), "\"cap-version-1\"")
                    : new NdmaCapAlerts.FetchResult(304, new byte[0], etag);
        }
    }
}

