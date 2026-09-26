package com.routiqo.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR 0074: forwarded client addresses are read only from trusted balancer hops, right to left. */
class ClientAddressResolverTest {
    static final String BALANCER_RANGES = "10.20.0.0/16, 10.21.0.0/16";
    static final String BALANCER = "10.20.3.4";
    static final String UNUSABLE = ClientAddressResolver.UNUSABLE_FORWARDING;

    static MockHttpServletRequest request(String peer, String... forwardedFor) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String header : forwardedFor) request.addHeader("X-Forwarded-For", header);
        return request;
    }

    static String resolve(String ranges, String peer, String... forwardedFor) {
        return new ClientAddressResolver(ranges).resolve(request(peer, forwardedFor));
    }

    @Test void offByDefaultKeepsTheSocketPeerAndIgnoresHeaders() {
        assertThat(resolve("", BALANCER, "198.51.100.7")).isEqualTo(BALANCER);
        assertThat(resolve(null, BALANCER, "198.51.100.7")).isEqualTo(BALANCER);
        assertThat(resolve("  ", "203.0.113.5")).isEqualTo("203.0.113.5");
    }

    @Test void untrustedPeerCannotSpoofAClientAddress() {
        assertThat(resolve(BALANCER_RANGES, "203.0.113.5", "198.51.100.7")).isEqualTo("203.0.113.5");
        assertThat(resolve(BALANCER_RANGES, "10.22.0.1", "198.51.100.7")).isEqualTo("10.22.0.1");
    }

    @Test void trustedBalancerHopGivesTheClient() {
        assertThat(resolve(BALANCER_RANGES, BALANCER, "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(resolve(BALANCER_RANGES, BALANCER, " 198.51.100.7 ")).isEqualTo("198.51.100.7");
    }

    @Test void trustedHopsAreSkippedFromTheRight() {
        assertThat(resolve(BALANCER_RANGES, BALANCER, "198.51.100.7, 10.21.9.9, 10.20.0.1"))
                .isEqualTo("198.51.100.7");
    }

    @Test void entriesLeftOfTheClientAreClientControlledAndNeverRead() {
        assertThat(resolve(BALANCER_RANGES, BALANCER, "1.2.3.4, 198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(resolve(BALANCER_RANGES, BALANCER, "not-an-address, 198.51.100.7")).isEqualTo("198.51.100.7");
        // A client that forges a trusted-looking hop still ends up keyed by its own address.
        assertThat(resolve(BALANCER_RANGES, BALANCER, "10.20.0.9, 198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test void severalHeaderLinesAreAmbiguousAndShareOneBucket() {
        // Which line a balancer appends to is not assumed, so a client cannot pick its key this way.
        assertThat(resolve(BALANCER_RANGES, BALANCER, "1.2.3.4", "198.51.100.7")).isEqualTo(UNUSABLE);
        assertThat(resolve(BALANCER_RANGES, BALANCER, "198.51.100.7", "10.20.0.1")).isEqualTo(UNUSABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "10.20.0.1", "10.20.0.1, 10.21.0.1", "198.51.100.7:443", "[2001:db8::1]",
        "unknown", "example.com", "198.51.100.7,", "198.051.100.7", "198.51.100", "256.1.1.1", "fe80::1%eth0",
        "::ffff:10.20.0.1"})
    void unusableRightmostEntrySharesOneBucket(String header) {
        assertThat(resolve(BALANCER_RANGES, BALANCER, header)).isEqualTo(UNUSABLE);
        // The same key whichever balancer node forwarded it, so junk never buys a bucket per node.
        assertThat(resolve(BALANCER_RANGES, "10.21.0.8", header)).isEqualTo(UNUSABLE);
    }

    @Test void missingHeaderSharesOneBucket() {
        assertThat(resolve(BALANCER_RANGES, BALANCER)).isEqualTo(UNUSABLE);
    }

    @Test void theLengthOfTheClientWrittenPartIsNeverRead() {
        String junk = String.join(",", java.util.Collections.nCopies(200, "1"));
        assertThat(resolve(BALANCER_RANGES, BALANCER, junk + ",198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(resolve(BALANCER_RANGES, BALANCER, junk + ",198.51.100.7,10.20.0.1")).isEqualTo("198.51.100.7");
    }

    @Test void trustedHopsAreBoundedAt32() {
        String hops = String.join(",", java.util.Collections.nCopies(32, "10.20.0.1"));
        assertThat(resolve(BALANCER_RANGES, BALANCER, "198.51.100.7," + hops)).isEqualTo("198.51.100.7");
        assertThat(resolve(BALANCER_RANGES, BALANCER, "198.51.100.7,10.21.0.1," + hops)).isEqualTo(UNUSABLE);
        assertThat(resolve(BALANCER_RANGES, BALANCER, hops)).isEqualTo(UNUSABLE);
    }

    @Test void ipv6ClientBehindIpv6BalancerHops() {
        assertThat(resolve("2600:1f18:aa::/48", "2600:1f18:aa:1::5", "2001:db8:5:6::9, 2600:1f18:aa:2::1"))
                .isEqualTo("2001:db8:5:6:0:0:0:0/64");
    }

    @Test void ipv6ClientsAreKeyedByTheirSlash64() {
        String first = resolve(BALANCER_RANGES, BALANCER, "2001:db8:1:2:aaaa::1");
        assertThat(first).isEqualTo("2001:db8:1:2:0:0:0:0/64");
        assertThat(resolve(BALANCER_RANGES, BALANCER, "2001:DB8:1:2:ffff:ffff:ffff:ffff")).isEqualTo(first);
        assertThat(resolve(BALANCER_RANGES, BALANCER, "2001:db8:1:3::1")).isNotEqualTo(first);
        assertThat(resolve("", "2001:db8:1:2:0:0:0:9")).isEqualTo(first);
    }

    @Test void ipv4MappedAddressesCollapseToIpv4() {
        assertThat(resolve(BALANCER_RANGES, BALANCER, "::ffff:198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(resolve(BALANCER_RANGES, "::ffff:10.20.3.4", "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test void ipv6BalancerRangesWork() {
        assertThat(resolve("2600:1f18:aa::/48", "2600:1f18:aa:1::5", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(resolve("2600:1f18:aa::/48", "2600:1f18:ab::5", "198.51.100.7")).isEqualTo("2600:1f18:ab:0:0:0:0:0/64");
    }

    @Test void nonLiteralPeerIsReturnedUnchanged() {
        assertThat(resolve(BALANCER_RANGES, "unix-socket", "198.51.100.7")).isEqualTo("unix-socket");
    }

    @Test void oddPrefixesMatchBitwise() {
        assertThat(resolve("10.20.0.0/17", "10.20.127.255", "198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(resolve("10.20.0.0/17", "10.20.128.0", "198.51.100.7")).isEqualTo("10.20.128.0");
        assertThat(resolve("10.20.3.4/32", BALANCER, "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "::/0", "10.0.0.0/7", "10.0.0.0/15", "2600::/15",
        "2600:1f18::/47", "::ffff:10.20.0.0/16", "::ffff:10.20.0.0/112", "10.20.0.0/016", "10.20.0.0", "10.20.0.0/33",
        "2600::/129", "10.20.0.0/-1", "10.20.0.0/+16", "10.20.0.0/016x", "10.20.0.0/16/16", "localhost/32",
        "balancer.internal/24", "10.20.0.1/16", "10.20.0.0/16,", ",10.20.0.0/16", "[::1]/128", "fe80::%1/64",
        "010.20.0.0/16", "10.20.0.0 /16"})
    void unsafeOrMalformedRangesAreRefusedAtStartup(String ranges) {
        assertThatThrownBy(() -> new ClientAddressResolver(ranges)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("ROUTIQO_TRUSTED_PROXY_CIDRS");
    }

    @Test void refusesToStartWithContainerForwardingOn() {
        var configuration = new ClientAddressConfiguration();
        assertThat(configuration.clientAddressResolver("", "none")).isNotNull();
        assertThat(configuration.clientAddressResolver(BALANCER_RANGES, "NONE")).isNotNull();
        for (String strategy : new String[] {"native", "framework", ""})
            assertThatThrownBy(() -> configuration.clientAddressResolver("", strategy))
                    .isInstanceOf(IllegalStateException.class);
    }

    @Test void atMost32Ranges() {
        String ok = String.join(",", java.util.Collections.nCopies(32, "10.20.0.0/16"));
        new ClientAddressResolver(ok);
        assertThatThrownBy(() -> new ClientAddressResolver(ok + ",10.21.0.0/16"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
