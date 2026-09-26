package com.routiqo.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Rate-limit key for the client behind a trusted load balancer (ADR 0074).
 *
 * <p>With no trusted ranges configured the key is the socket peer, as before. With ranges configured,
 * {@code X-Forwarded-For} is read only when the socket peer is inside one of them, walking from the
 * right past trusted hops; everything left of the first untrusted entry is client-controlled and never
 * read. Anything unexpected falls back to the socket peer. Only literal addresses are parsed, so no DNS
 * lookup can happen. IPv6 keys are the /64 prefix. Addresses are never logged.
 */
public final class ClientAddressResolver {
    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final int MAX_RANGES = 32;
    static final int MAX_FORWARDED_ENTRIES = 32;
    private static final int MIN_IPV4_PREFIX = 8;
    private static final int MIN_IPV6_PREFIX = 16;
    private static final int MAX_LITERAL_LENGTH = 45;

    private final List<Range> trusted;

    public ClientAddressResolver(String trustedRanges) {
        this.trusted = parseRanges(trustedRanges);
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        byte[] peerAddress = literal(peer);
        if (peerAddress == null) return peer;
        String peerKey = key(peerAddress);
        if (trusted.isEmpty() || !isTrusted(peerAddress)) return peerKey;

        var entries = new ArrayList<String>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            for (String entry : header.split(",", -1)) {
                if (entries.size() == MAX_FORWARDED_ENTRIES) return peerKey;
                entries.add(entry.trim());
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            byte[] hop = literal(entries.get(i));
            if (hop == null) return peerKey;
            if (!isTrusted(hop)) return key(hop);
        }
        return peerKey;
    }

    private boolean isTrusted(byte[] address) {
        for (Range range : trusted) if (range.contains(address)) return true;
        return false;
    }

    /** IPv4 in dotted form; IPv6 as its /64 prefix, so one device can't rotate through its own /64. */
    static String key(byte[] address) {
        try {
            if (address.length == 4) return InetAddress.getByAddress(address).getHostAddress();
            byte[] prefix = Arrays.copyOf(Arrays.copyOf(address, 8), 16);
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Parses a literal address without any name lookup: strict dotted-quad IPv4, or IPv6 with no zone,
     * brackets or port. IPv4-mapped IPv6 becomes IPv4. Returns null for anything else.
     */
    static byte[] literal(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LITERAL_LENGTH) return null;
        if (text.indexOf(':') < 0) return ipv4(text);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return null;
        }
        try {
            // ofLiteral never resolves names; an IPv4-mapped literal comes back as a 4-byte Inet4Address.
            return Inet6Address.ofLiteral(text).getAddress();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static byte[] ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) return null;
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') return null;
                value = value * 10 + (c - '0');
            }
            if (value > 255) return null;
            address[i] = (byte) value;
        }
        return address;
    }

    private static List<Range> parseRanges(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String[] entries = raw.split(",", -1);
        if (entries.length > MAX_RANGES)
            throw new IllegalArgumentException("ROUTIQO_TRUSTED_PROXY_CIDRS allows at most " + MAX_RANGES + " ranges");
        var ranges = new ArrayList<Range>();
        for (String entry : entries) ranges.add(Range.parse(entry.trim()));
        return List.copyOf(ranges);
    }

    record Range(byte[] network, int prefix) {
        static Range parse(String text) {
            int slash = text.indexOf('/');
            if (slash < 0 || slash != text.lastIndexOf('/'))
                throw invalid("each trusted range needs exactly one /prefix");
            byte[] network = literal(text.substring(0, slash));
            if (network == null) throw invalid("trusted ranges must be literal IPv4 or IPv6 addresses");
            String digits = text.substring(slash + 1);
            if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(c -> c >= '0' && c <= '9'))
                throw invalid("invalid prefix length");
            int prefix = Integer.parseInt(digits);
            int bits = network.length * 8;
            int minimum = network.length == 4 ? MIN_IPV4_PREFIX : MIN_IPV6_PREFIX;
            if (prefix > bits) throw invalid("prefix length out of range");
            if (prefix < minimum) throw invalid("trusted ranges wider than /" + minimum + " are refused");
            if (hasHostBits(network, prefix)) throw invalid("trusted range has host bits set");
            return new Range(network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) if (address[i] != network[i]) return false;
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[whole] & mask) == (network[whole] & mask);
        }

        private static boolean hasHostBits(byte[] network, int prefix) {
            for (int bit = prefix; bit < network.length * 8; bit++)
                if ((network[bit / 8] & (0x80 >>> (bit % 8))) != 0) return true;
            return false;
        }

        private static IllegalArgumentException invalid(String reason) {
            return new IllegalArgumentException("ROUTIQO_TRUSTED_PROXY_CIDRS: " + reason);
        }
    }
}
