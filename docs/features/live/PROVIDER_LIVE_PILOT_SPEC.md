# Provider LIVE pilot: Chennai district alerts

Status: implemented locally behind a default-off backend flag; staging activation and real authenticated/provider QA remain pending. This is a provider-only active-journey list and does not activate traveller-derived Live Moments or publish private Quick Signals.

> **Direction brief (2026-09-25):** Kept, optional: official alerts are shown on Spots and the Journey when available. Coverage names only Chennai-area districts; per the 2026-09-25 decision it is extended to the trunk and branch corridor districts before the Diwali 2026 dry run (north-east monsoon season), and to the Coimbatore trunk before Pongal. See [PRODUCT.md](../../PRODUCT.md).

## Scope and acceptance

- For an authenticated active journey, show at most 10 current official CAP weather/disaster alerts that explicitly name Chennai, Chengalpattu, Kanchipuram or Tiruvallur district, or the entire state of Tamil Nadu. The pilot is district/state-wide, not a claim that an alert applies to the user's particular road or exact route. Do not inspect device GPS or private signal receipts to populate it.
- Source: NDMA SACHET [India CAP RSS feed](https://sachet.ndma.gov.in/cap_public_website/rss/rss_india.xml). Follow only numeric CAP identifiers at the fixed NDMA host. Require public, actual, non-cancelled CAP records with valid effective/expiry times, English event and a matching area. Attribute the issuer and link to the official CAP record. Show issue/freshness and expiry without claiming traffic or physical presence.
- Bound feed and CAP response size, item count, title and text length, redirects and deadlines. Disable XML DTD/external entities. Reuse ETags for CAP details per NDMA's [integration guide](https://sachet.ndma.gov.in/docs/Integration_Guide_For_Agencies.pdf). Cache only as an efficiency measure; expiry and owner authorization are rechecked on every read.
- The backend authenticates the browser session, checks account context and current owned ACTIVE journey before and after provider access, applies a shared database rate gate, and returns `Cache-Control: no-store`. Missing auth, completed journey, feed outage or invalid source data fail closed. No user identifiers or route details go to NDMA.
- The browser refreshes only in the foreground, with one request in flight, at most once per minute, cancellation on account/journey/visibility/offline changes, and no persistence. Empty, loading, error, offline, stale and expiry states are explicit. Offline rows remain in memory until their expiry and are labelled stale. Private Quick Signal controls remain separate and do not imply public contribution.
- Test XML rejection, stale/cancelled/wrong-area alerts, ETag/304, source outage, authorization and lifecycle changes, bounded reads, browser cancellation and accessible narrow-screen presentation. Real pilot QA must confirm NDMA coverage and attribution before enabling the flag in staging/production.

Provider alerts provide useful independent information but do not satisfy the future traveller-derived publication protocol in ADRs 0038 and 0049. The provider feed may have no active Chennai alert; the list must say so rather than invent a situation.

## Deferred staging validation record

Recorded 2026-09-23. The user will validate this pilot later. Keep `ROUTIQO_PROVIDER_LIVE_ENABLED` off outside a controlled staging test until the checks below are recorded. Local automated checks and a source-format smoke check are documented in `docs/quality/BUILD_STATUS.md`; they do not complete this gate.

- [ ] With real Google sign-in, start an owned journey and confirm that its official-alert request succeeds; confirm another account, a completed journey and a signed-out browser cannot read it.
- [ ] Confirm an empty NDMA result says no current alert was returned without implying that roads are safe. When NDMA has an applicable Chennai-area or Tamil Nadu alert, compare the displayed event, area, issuer, expiry and link against the source CAP record. Record the observation time and source identifier.
- [ ] Exercise an NDMA outage, slow response, rate limit, offline transition, reconnect, account switch and journey completion. Check that rows are labelled stale or cleared as specified and that no private Quick Signal data is sent to NDMA or included in the response.
- [ ] Inspect the rendered Trips panel on a narrow screen and with large text, keyboard navigation and a screen reader. Check loading, empty, alert, error and offline states.
- [ ] Record staging response times and the pilot flag/configuration used. Enable the flag for a wider audience only after the results and any fixes are reviewed.

Validation result: **pending user-led staging QA**. Record findings and date here before changing this status.
