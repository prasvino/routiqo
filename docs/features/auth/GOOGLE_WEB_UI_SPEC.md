# Google browser login UI and proxy

Visual thesis: retain Profile's quiet ivory/forest styling; account status is a compact utility row, not a new hero.
Content plan: account state and one sign-in/sign-out action; short device-only planning note; existing saved places/data controls remain below.
Interaction thesis: native dialog focus/Escape behavior, Google's own accessible button, explicit loading/error/retry states; reuse existing restrained transitions and reduced-motion rules.

Implement an allowlisted same-origin Next route for the five backend auth paths. Configuration is server-owned: ROUTIQO_AUTH_API_URL, ROUTIQO_GOOGLE_CLIENT_ID and ROUTIQO_WEB_ORIGIN. Only enabled/clientId are returned to UI. Missing configuration shows an honest local-preview state without loading Google. Validate server URLs, reject unsupported paths/query strings and cross-origin writes, forward only auth cookies/CSRF/Origin/content-type, preserve separate Set-Cookie headers, never follow redirects and bound request/response size/time. No user-supplied upstream URL or forwarded peer headers.

Load Google Identity Services only after the user opens sign-in. Bootstrap CSRF and challenge; initialize Google's button with client ID and the stored challenge nonce; exchange the credential using same-origin cookies. Never store Google tokens or Routiqo session credentials in localStorage. Render signed-in state only after backend confirmation. Retry expired challenge explicitly. Signed-in state does not imply plan sync. Sign-out keeps local plans/bookmarks.

Tests: proxy allowlist/origin/size/timeout/cookie behavior against controlled local upstream or injected fetch; auth response validation/error mapping; TypeScript/build checks; browser-rendered unconfigured state and existing profile behavior. Real Google consent/popup/success remains blocked until actual OAuth setup. Do not fake production login for visual QA.
