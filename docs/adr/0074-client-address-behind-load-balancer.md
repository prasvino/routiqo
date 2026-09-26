# ADR 0074: Client address for rate limits behind a load balancer

Date: 2026-09-26
Status: accepted; implemented default-off (step A of the Diwali critical path).
Amends the "socket peer only" rule of [ADR 0009](0009-browser-auth.md) for
rate-limit keys only.

## Context

Three guards apply a per-address rate gate before any other work:

| Guard | Categories and limits per minute |
|---|---|
| `NativeAuthGuard` | `native-challenge` 10, `native-exchange` 20, `native-other` (every signed-in native path) |
| `BrowserAuthGuard` | `challenge` 10, `exchange` 20, `other` 120 |
| `AdminTrafficGuard` | `admin-challenge` / `admin-exchange` 10, `admin-transport` 100 |

All three keyed on `request.getRemoteAddr()`, and both auth profiles set
`server.forward-headers-strategy: none`. ADR 0009 kept the socket peer "until
trusted proxy deployment is designed" and required proxy aggregation to be
evaluated before deployment.

Behind the planned AWS Application Load Balancer, the socket peer is always a
balancer node. Every phone would share one bucket, and about 40 travellers
polling Spot activity every 20 s would get 429 on every native path, session
renew included. The Spots backend review (BUILD_STATUS, *September 26 Spots
backend*) recorded this as the open Medium finding that blocks turning
`ROUTIQO_SPOTS_API_ENABLED` on in staging.

A second, separate aggregation remains even with the true client address:
Indian mobile carriers put many IPv4 subscribers behind one carrier-grade NAT
address. IPv6 subscribers usually get their own /64.

## Decision

1. **`ClientAddressResolver`** (`com.routiqo.core.security`) supplies the
   rate-limit key for all three guards. It is configured by one setting,
   `ROUTIQO_TRUSTED_PROXY_CIDRS`: a comma-separated list of literal CIDRs.
   - **Unset or blank (the default):** the key is the socket peer, as before.
   - **Set:** `X-Forwarded-For` is read only when the socket peer is inside a
     trusted range. All header lines are read in order and split on commas.
     Entries are then walked from the **right**, skipping trusted hops, and the
     first untrusted entry is the client. Everything to its left was written
     by the client and is never read.
   - **Fail safe to the peer** when the header is missing, all entries are
     trusted, the chain has more than 32 entries, or the entry that would be
     used is not a literal address (ports, brackets, zones, names, empty
     entries or leading zeros). The peer is the balancer's shared bucket, so a
     malformed header never earns a fresh bucket.
2. **No name resolution.** IPv4 is parsed as a strict dotted quad. IPv6 must
   contain only hex digits, `:` and `.` before `Inet6Address.ofLiteral`, which
   never performs a lookup.
3. **Startup validation**, failing fast:
   - every entry needs exactly one `/prefix`;
   - no host bits may be set;
   - IPv4 ranges wider than /8 and IPv6 ranges wider than /16 are refused
     (which excludes `0.0.0.0/0` and `::/0`);
   - at most 32 ranges.
4. **Key normalisation** in both modes:
   - IPv4 is keyed as dotted form;
   - IPv4-mapped IPv6 collapses to IPv4;
   - IPv6 is keyed by its **/64 prefix**, so one device cannot rotate through
     its own /64 to multiply its budget, and separate subscribers' /64s stay
     separate.
   Keys are HMAC-masked by `JdbcAuthRateGate` as before, and addresses are
   never logged.
5. **Why not Tomcat `RemoteIpValve`** (`forward-headers-strategy: native`):
   - It also rewrites scheme, host and `isSecure()`, which affects cookies,
     redirects and CSRF origin checks.
   - Its default internal-proxy pattern trusts every private range, including
     the carrier-NAT range `100.64.0.0/10`.

   The resolver changes only the rate-limit key, has an explicit allowlist, and
   is unit-tested without a container. `forward-headers-strategy` stays `none`.
6. **Signed-in native ceiling: 600 per minute per address** (owner decision,
   2026-09-26) for `native-other`. This applies with or without trusted ranges.
   - Challenge (10) and exchange (20) are unchanged; they are the
     unauthenticated abuse surface.
   - The per-account `native-account` limit (120 per minute) and every
     endpoint's own per-account limit are unchanged, so no single account gains
     budget.
   - On signed-in paths the per-address gate only protects the indexed session
     lookup, which already requires a well-formed 43-character bearer
     credential.
   - Browser and admin limits are unchanged: in the pilot they are not phone
     traffic behind carrier NAT.

## Staging configuration

- Set `ROUTIQO_TRUSTED_PROXY_CIDRS` to the **ALB's subnet CIDRs** in the VPC
  (for example `10.0.1.0/24,10.0.2.0/24`), never a public or catch-all range.
- The API must be reachable only through the ALB (security group). Otherwise a
  host inside those subnets could forge the header.
- The ALB appends the client address to `X-Forwarded-For` (its default
  `append` mode). If CloudFront or another proxy is added in front later, its
  ranges need a separate decision; this ADR does not trust them.

## Consequences

- With the setting on, each phone (or carrier-NAT address) has its own buckets,
  and the peer gate no longer turns into a site-wide 429 behind the balancer.
- Remaining risks, to measure in staging:
  - Sign-in bursts behind one IPv4 carrier-NAT address are still capped at 10
    challenges and 20 exchanges per minute.
  - At festival peak, a Spot activity read costs three rate-bucket upserts, one
    session lookup and one journey `EXISTS`. Its database cost is unmeasured.
- Behaviour change with the setting off: direct IPv6 peers are now keyed by
  /64 and IPv4-mapped peers by IPv4. That is stricter per device but never
  shares buckets between different /64s.

## Verification

- `ClientAddressResolverTest`: off mode, spoofing from an untrusted peer,
  right-to-left walk, left-side forgery, multiple headers, fallbacks, IPv6 /64,
  mapped addresses, bitwise prefixes and startup rejection.
- `TrustedProxyRateHttpTest` (real PostgreSQL, trusted loopback):
  - separate buckets per forwarded client on the native challenge;
  - prepending does not escape;
  - a missing or garbage header uses the balancer's bucket;
  - the 600 signed-in ceiling;
  - the browser guard uses the same key.
- `NativeAuthHttpTest`: the header is ignored when no ranges are configured.
- Not verified: a real ALB in staging, and the admin guard over HTTP. The admin
  guard shares the resolver and the same one-line call, but has no dedicated
  HTTP test.
