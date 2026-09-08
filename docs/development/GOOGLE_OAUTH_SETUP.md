# Google OAuth setup for Routiqo

The login UI/proxy is implemented, but no real OAuth client is configured. Google client IDs are public identifiers; client secrets must never be put in browser/mobile code or task messages. No client secret is required by this Google Identity Services ID-token flow.

## Google project configuration
1. In your Google Cloud project, configure the OAuth consent/branding screen for Routiqo and required contact details. While the app is in testing, add the Google accounts that should test it.
2. Create an OAuth client of type Web application.
3. Add the exact authorized JavaScript origins used by the web app. For local testing use http://localhost:3000 (and http://localhost when required by Google's local setup guidance). Use the same hostname consistently; the preview URL 127.0.0.1 is a different origin.
4. For production add the real HTTPS origin. This implementation uses Google's JavaScript popup callback, not a server redirect callback; do not invent a redirect path.
5. Copy the public client ID ending in .apps.googleusercontent.com. Google registration, consent and actual sign-in must be verified with your configured account; no fake account is supplied by Routiqo.

## Web server environment
Supply server-side environment values before starting Next:
- ROUTIQO_GOOGLE_CLIENT_ID: the Web client ID above
- ROUTIQO_AUTH_API_URL: core API origin, such as http://127.0.0.1:8080 for local development
- ROUTIQO_WEB_ORIGIN: exact browser origin, such as http://localhost:3000

Missing configuration keeps Profile in local-preview mode and does not load Google's script. Restart Next after changing environment variables. Only the public client ID and enabled state are returned to the browser; API origin/secrets remain server-side.

## Core API environment
Use persistence,google-auth,web-auth profiles and the same Google client ID and browser origin. Supply database credentials and a randomly generated shared ROUTIQO_AUTH_RATE_SECRET. Local HTTP requires ROUTIQO_AUTH_SECURE_COOKIES=false; production defaults to Secure cookies and requires HTTPS. See LOCAL_SETUP.md. Migrations apply to the chosen database when this profile starts, so verify its URL first.

## Verification
Open the configured origin, visit Profile and choose Sign in. The page loads Google's own button only after this action. Check success, cancellation, expired attempt, sign-out and reload. No cloud-plan sync is implied. Visible pages renew valid sessions near expiry, up to 12 hours from Google authentication. Inactivity beyond 15 minutes requires another sign-in. Also verify account deletion requires fresh sign-in and explicit reconfirmation; use a disposable test account. Mobile OAuth/client registration is a separate pending integration.

References checked: https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid and https://developers.google.com/identity/gsi/web/reference/js-reference .
