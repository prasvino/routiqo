# Google sign-in — first integration slice

Google is the first login method. Phone OTP is deferred. Implement server-side Google ID-token verification before session exchange or client login UI. Use Spring Security's managed JOSE/Nimbus implementation, RS256 only, Google's fixed HTTPS JWKS endpoint, explicit claims validation and bounded network timeouts. No tokeninfo HTTP call and no user-supplied issuer/key URL.

Require a configured client ID for audience and authorized-party checks, a bounded stable subject, issued/expiry timestamps and a matching server-generated login nonce. Accept Google's two documented issuer forms. Reject malformed, expired, future-issued, wrong-audience, wrong-presenter, wrong-nonce or incorrectly signed tokens. A verified subject is an external identity, not a Routiqo UUID or access token. Never link by email. Never log raw credentials or return provider parsing errors.

Nonce creation, expiry and atomic one-time consumption belong to the upcoming session exchange. This verifier alone does not prevent replay; its caller must use a persisted login challenge bound to the initiating browser/device. Web exchange also requires same-origin/CSRF validation. No endpoint is enabled in this slice; existing protected routes remain denied.

Acceptance: signed-token tests using fresh local RSA keys exercise real signature verification and negative claims; default preview still boots without Google configuration; explicit google-auth profile requires external client ID. Provider fetch failure must fail closed. No actual Google account, SMS, credentials or cloud resources required for tests.

Next: durable identity mapping, expiring one-time login challenges, revocable sessions and cookie/CSRF policies, authenticated contracts, Google client UI and native secure storage. Configure Google OAuth consent/client IDs and authorized origins before real end-to-end login.

## Account mapping in this pass

V2 migration creates routiqo_account with UUID, unique Google subject, enabled state and creation time. A single PostgreSQL upsert resolves concurrent first login; disabled accounts fail without reactivation. No email or phone is stored. Integration tests use a disposable PostgreSQL database. No migration is applied to the developer's existing Compose volume by tests. Account deletion/retention, session revocation and journey-owner FK enforcement remain gates before public writes.
