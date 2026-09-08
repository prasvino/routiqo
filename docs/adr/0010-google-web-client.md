# 0010 — Same-origin Google web client

Use the existing Next route-handler runtime as a narrow authentication proxy. Allow only declared auth paths and methods; forward auth cookies and CSRF/origin headers only. Never proxy a client-supplied URL, follow redirects or forward arbitrary authorization/forwarded headers. Bound bodies and upstream timeout. Preserve each Set-Cookie header separately and force no-store.

Read configuration on the server. Missing configuration is a normal preview state, not a fake login. Load Google's official Identity Services script only after a sign-in action; render its own branded button with server challenge nonce. Tokens stay in memory only for exchange. Backend confirmation determines account state; sign-out does not delete local plans.

No additional dependency is needed. Google's popup/consent and actual account success remain unverified until OAuth registration exists. Proxy/client tests use isolated response fixtures; browser QA verifies the default configuration state and existing profile content.
