# 0008 — One-time Google exchange and opaque sessions

Build an internal exchange on the existing PostgreSQL transaction boundary. Google verification runs outside transactions, followed by a locked challenge recheck and atomic account resolution, consumption and session insert. Concurrent exchanges have one winner. A failed commit returns no credential and leaves no partially consumed challenge.

Use independent 256-bit random challenge nonce/binding and opaque session credential. Persist only SHA-256 digests of binding/session secrets. Store the nonce because the verifier needs it; the nonce alone cannot authenticate. Avoid verbose record string representations that expose secrets.

Challenge lifetime is five minutes. Sessions last 15 minutes, are revocable and check enabled-account state on every lookup. No sliding expiry or refresh token exists yet. Rotation and secure browser/native transport remain explicit follow-up work; do not expose an endpoint prematurely.

Tables include expiry indexes for future bounded cleanup and an account foreign key with session cascade deletion. Row retention/cleanup and login rate limiting must be implemented before public exposure. No process-local session authority or new service dependency.
