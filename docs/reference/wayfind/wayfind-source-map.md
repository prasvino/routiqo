# Wayfind source map and discrepancies

Reviewed: 2026-09-06. Companion: [implementation guardrails](./wayfind-implementation-guardrails.md).

## Start here

| Source | Use |
|---|---|
| [Frontend overview](D:/Pras/wayfind-journal/instructions/overview.md) | May 1 product/package snapshot, recap/replay/import/chat, web/mobile differences |
| [Backend overview](D:/Pras/wayfind-api/instructions/overview.md) | May 1 backend domains and contracts; documentation, not runtime proof |
| [Frontend startup guide](D:/Pras/wayfind-journal/instructions/instructions.md) | Package ownership, shared contract workflow, checks; February facts may be stale |
| [Backend startup guide](D:/Pras/wayfind-api/instructions/instructions.md) | Layering, migrations, auth, uploads, events, testing |
| [Frontend architecture](D:/Pras/wayfind-journal/instructions/frontend-architecture.md) | Web/native/shared boundaries and independent deployment targets |

## Topic references

| Topic | Sources |
|---|---|
| Auth and refresh transports | [API auth](D:/Pras/wayfind-api/instructions/auth.md), [frontend auth](D:/Pras/wayfind-journal/instructions/auth.md) |
| Account lifecycle | [Deletion specification](D:/Pras/wayfind-api/instructions/account-deletion-system-specification.md), [readiness plan](D:/Pras/wayfind-journal/instructions/plan.md) |
| Realtime reliability | [Cross-replica plan](D:/Pras/wayfind-journal/instructions/ToDoFeb28.md), [idle-time fixes](D:/Pras/wayfind-journal/instructions/idleTime.md) |
| Search and mapping | [Search handover](D:/Pras/wayfind-api/instructions/search_impl.md), [places walkthrough](D:/Pras/wayfind-api/instructions/places_walkthrough.md), [derived metrics fix](D:/Pras/wayfind-api/instructions/fixStaticfields.md) |
| Historical gaps | [February findings](D:/Pras/wayfind-journal/instructions/feb21findings.md), [March findings](D:/Pras/wayfind-journal/instructions/mar1findings.md), [earlier review](D:/Pras/wayfind-journal/instructions/myfindings.md), [earlier backlog](D:/Pras/wayfind-journal/instructions/claudeToDo.md) |
| Performance intent | [Performance plan](D:/Pras/wayfind-journal/instructions/performance-implementation-plan.md) |
| Release and hosting | [README](D:/Pras/wayfind-journal/README.md), [go-live plan](D:/Pras/wayfind-journal/LIVE.md), [Vercel/Azure guide](D:/Pras/wayfind-journal/docs/VERCEL_AZURE_SETUP.md) |
| Earlier product direction | [Redesign strategy](D:/Pras/wayfind-journal/redesign-apr3.md); historical options do not replace the TrailSangam mock |

## Concrete discrepancies and interpretation

| Item | Evidence from this review | Treatment |
|---|---|---|
| Temporary identity header | Early [frontend prompts](D:/Pras/wayfind-journal/frontend_instructions.md) require `X-User-Id`; later auth docs remove it and current client sends bearer tokens | Obsolete bootstrap approach |
| Auth implemented last | Early [backend prompts](D:/Pras/wayfind-journal/backend-impl-instructions.md) prescribe auth last | Historical sequencing, not a guardrail for a new app handling presence/chat |
| TypeScript strictness | [Web config](D:/Pras/wayfind-journal/packages/web/tsconfig.app.json) sets `strict: false` and `noImplicitAny: false`; [shared config](D:/Pras/wayfind-journal/packages/shared/tsconfig.json) sets `strict: true` | Intended rule is only partially enforced |
| User journey endpoint | [userService](D:/Pras/wayfind-journal/packages/shared/src/services/userService.ts:100) calls `/journeys?authorId=...`; [journeyService](D:/Pras/wayfind-journal/packages/shared/src/services/journeyService.ts:391) calls `/journeys/user/{id}` | Current source divergence; backend acceptance of both was not tested |
| Origin/destination | February startup guide describes title parsing; [current mapper](D:/Pras/wayfind-journal/packages/shared/src/services/journeyService.ts:567) prefers structured fields and retains parsing fallback | Do not describe title parsing as the current primary contract |
| Location provider | [Older issue plan](D:/Pras/wayfind-api/instructions/issuefix.md) suggests uncached direct Nominatim calls; [current service](D:/Pras/wayfind-journal/packages/shared/src/services/locationService.ts) calls backend location endpoints | Follow current boundary; independently validate provider policy before implementation |
| WebSocket protocol | Startup guide describes SockJS; later idle-time fix describes removing it; [current client](D:/Pras/wayfind-journal/packages/shared/src/services/websocketService.ts) uses STOMP client resilience settings | Verify backend handshake before integration |
| Realtime readiness | March findings recommend a broker change; February detailed plan already documents a Kafka relay, and deployment guide lists relay flags | Assess actual configuration and cross-replica tests; do not infer a missing implementation from an old finding |
| Mobile version/maturity | March findings mention Expo 51; [current manifest](D:/Pras/wayfind-journal/packages/mobile/package.json) has Expo 54, RN 0.81.5, React 19.1 | Use manifests and current feature inspection, not historical screen counts |
| Local web port | Old prompts mention 5173; [Vite config](D:/Pras/wayfind-journal/packages/web/vite.config.ts) sets 8081 and proxies backend 8080 | Current config wins |
| Account recovery | Deletion specification says block login, then describes login-triggered reactivation; also leaves content deletion/anonymization as alternatives | Incomplete policy decision, not an executable specification |
| Test coverage claims | [Root manifest](D:/Pras/wayfind-journal/package.json) runs tests only for shared/web | Do not equate root tests with mobile validation or production readiness |

## Source code spot-checks

- [Shared API client](D:/Pras/wayfind-journal/packages/shared/src/api/client.ts): bearer auth, response envelope unwrapping, bounded 401 retry, normalized errors.
- [Auth service](D:/Pras/wayfind-journal/packages/shared/src/services/authService.ts): runtime transport selection and concurrent refresh promise.
- [Native token adapter](D:/Pras/wayfind-journal/packages/mobile/src/auth/secureTokenStorage.ts): Expo SecureStore path and a distinct browser fallback.
- [Notification hook](D:/Pras/wayfind-journal/packages/shared/src/hooks/useNotifications.ts): realtime/polling lifecycle.
- [WebSocket service](D:/Pras/wayfind-journal/packages/shared/src/services/websocketService.ts): heartbeat, reconnect delay changes, visibility recovery.

## Limits of this reference

This was a documentation and focused source review, not an exhaustive review of every component. Instruction-folder inventories and targeted searches were used to locate relevant material; not every historical file was read line by line. No backend source outside its instruction directory was audited, no live deployment was inspected, and no source repositories were modified. Historical claims of completed work or successful tests have not been independently reproduced. New TrailSangam-specific rules are explicitly labeled as proposed adaptations in the companion document.
