# ADR 0020: Separate native authentication namespace

Status: accepted; backend boundary and response validators implemented, 2026-09-12.

Expose native authentication only through an opt-in `/api/v1/native/auth`
namespace. Reuse existing Google/session services and database-backed abuse controls,
with no browser cookie fallback and no weakening of browser CSRF. Only native
responses contain opaque credentials and challenge bindings. Browser journey and
route APIs remain cookie-only; native resource authorization is a subsequent change.

Rejecting browser headers does not prove a request comes from the app. Bearer
possession and server-side verification remain the authorization boundary.

The first client addition is strict response normalization for the secure vault.
Do not mount React Native fetch as a credential adapter until no-redirect/cookie
behavior is enforceable and tested on-device. React Native documents fetch redirect
limitations: https://reactnative.dev/docs/network. No certificate bypass or silent
HTTP fallback is introduced to avoid this gate.

See `docs/features/auth/NATIVE_AUTH_HTTP_SPEC.md` for acceptance and remaining work.
