# 0007 — Google identity verification

User selected Google first; phone OTP is deferred. Verify Google ID tokens directly through Spring Security JOSE, managed by the existing Boot BOM. This does not select a separate managed user directory or introduce another cloud service. Routiqo session issuance and account storage remain a subsequent explicit boundary.

Use Google's fixed JWKS endpoint and RS256, with explicit issuer/audience/authorized-party/time/subject validation. Require the login challenge nonce at verification. Return only the provider and stable subject; email is not an identity key. Cryptographic tests generate ephemeral RSA keys locally; no static private key fixtures.

No public exchange endpoint or bearer-token acceptance is added until one-time challenges, account mapping, session revocation and web CSRF are implemented. Direct Google tokens must never become long-lived Routiqo API credentials.

References: https://developers.google.com/identity/gsi/web/guides/verify-google-id-token and https://developers.google.com/identity/openid-connect/openid-connect . Spring reference: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html .
