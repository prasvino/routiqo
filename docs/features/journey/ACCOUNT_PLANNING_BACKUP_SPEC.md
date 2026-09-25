# Account planning backup: first cross-device slice

Status: implemented behind default-off flags. Verification results are in `docs/quality/BUILD_STATUS.md`.
Decision record: ADR 0062.

## Purpose and scope

Journey plans and saved places live only in one browser (`routiqo.planning.v1`). A signed-in traveller who changes device, reinstalls a browser or clears site data loses them. The only recovery today is a downloaded JSON file.

This slice lets a verified account keep **one explicit, owner-only copy** of its planning state on the server:

- Save this device's plans and saved places to the account copy.
- Add the account copy's plans and saved places to another device, without overwriting local work.
- Remove the account copy.

It is a deliberate backup, not automatic background sync. Nothing is uploaded unless the traveller asks. A saved local plan never triggers an upload.

Out of scope:
- automatic or continuous sync;
- per-plan merge or conflict editing;
- native Android controls (these reuse the same contract in a later slice);
- sharing plans with other people;
- server use of plan content for recommendations, LIVE or AI;
- journeys and journals, which have their own account storage.

## User flow (web Profile)

The panel appears only when the UI flag is on and a Google account is verified. It never makes a request on its own.

1. **Check account copy** reads the copy and shows one of:
   - "No plans saved on your account yet";
   - the account copy's plan count, saved-place count and last-saved time.
2. **Save this device's plans to your account**:
   - With no account copy, one tap saves.
   - When an account copy exists, the panel first asks the traveller to confirm replacing it. The prompt shows both counts. The write carries the version that was checked.
3. **Add account plans to this device** uses the existing backup merge rule:
   - local plans are kept;
   - plans with new IDs are added;
   - saved places are combined;
   - the 100-plan and 100-place limits apply, and exceeding either rejects the whole merge.
   The panel reports what was added and what was kept.
4. **Remove account copy** needs a confirmation. It deletes only the server copy; local data is untouched.

States the panel must handle:
- **Loading:** the action button shows progress and other actions are disabled.
- **Offline:** actions are disabled and the panel says the account copy needs a connection.
- **Session ended:** local data is kept and the panel asks the traveller to sign in again.
- **Rate limited:** the panel asks the traveller to wait a minute.
- **Too large:** a local state bigger than the upload limit is refused before sending, with a request to remove plans or shorten notes.
- **Conflict:** the copy changed on another device. The checked result is discarded and the traveller must check again before replacing.
- **Uncertain save:** after a timeout or network failure, the exact same request is kept in memory. **Retry save** resends it unchanged, same mutation ID included, and a server replay returns the original result. Checking again discards the pending retry.

Changing account, signing out, deleting the account or leaving Profile aborts in-flight requests. It also clears the account-copy view and any pending retry, and late responses are ignored.

## Contract

These endpoints require the `persistence` and `web-auth` profiles, plus the server flag `ROUTIQO_PLANNING_BACKUP_API_ENABLED=true`. With the flag off, the security chain denies the paths (401 through its entry point), the controller is not created, and the Next proxy returns 404.

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /api/v1/planning` | none | 200 `AccountPlanning` | 401 |
| `POST /api/v1/planning` | `AccountPlanningWrite` | 200 `AccountPlanning` | 400, 401, 403, 409, 413, 415, 429 |
| `POST /api/v1/planning/delete` | `AccountPlanningDelete` | 204 | 400, 401, 403, 409, 413, 415, 429 |

Every call needs the session cookie and exactly one `X-Routiqo-Account` header matching the session. POST also needs `Origin`, CSRF and `application/json`. Responses are `no-store`.

- `AccountPlanning` is `{version, updatedAt, plans, saved}`.
  - Version 0 means there is no account copy: `updatedAt` is null and both lists are empty.
- `AccountPlanningWrite` is `{plans, saved, expectedVersion, mutationId}`.
  - The write is a compare-and-swap: it succeeds only when `expectedVersion` equals the stored version (0 for none).
  - A request repeating the same mutation ID, expected version and content returns the stored result without writing again.
  - Reusing a mutation ID with different content, or sending a stale version, returns 409.
- `AccountPlanningDelete` is `{expectedVersion}`, with `expectedVersion` ≥ 1.
  - A version match deletes the copy.
  - If no copy exists, the call succeeds (idempotent).
  - A mismatch returns 409.

## Validation (server)

The server rejects rather than repairs:
- unknown or duplicate fields;
- wrong JSON types;
- non-integer numbers;
- invalid Unicode (lone surrogates);
- control characters in single-line fields.

Field rules:
- `plans`: at most 100, with unique IDs.
- Plan `id`: 1–100 characters.
- `kind`: `trip` or `commute`.
- `origin` and `destination`: 1–100 characters, not blank after trimming.
- `date`: a real calendar date in `YYYY-MM-DD`.
- `time`: `HH:MM` on a 24-hour clock.
- `days`: unique integers 0–6. A commute needs at least one day.
- `notes`: at most 500 characters. Tab and newline are allowed.
- `createdAt`: 1–64 characters.
- `saved`: at most 100 unique IDs matching `[a-z0-9-]{1,80}`.

The stored document may not exceed 256 KiB of UTF-8 JSON. The browser guard and the Next proxy accept request bodies up to 264 KiB (256 KiB plus a small envelope allowance) for `POST /api/v1/planning` only. Every other browser POST keeps the 20 KiB limit.

The client runs the full `readPlanningState` validation on everything it receives. It refuses the whole copy rather than partially merging.

## Data, retention and deletion

Migration V28 adds table `account_planning_copy`, with one row per account:
- `plans` and `saved` (JSONB);
- `document_bytes` (≤ 256 KiB);
- `version`, `updated_at` and `latest_mutation_id`;
- a foreign key to `routiqo_account` with `ON DELETE CASCADE`.

The copy is kept until the traveller removes it or deletes the account. Signing out keeps it. The content is used only to return it to its owner. It is never logged (`toString` is redacted), never exposed to other accounts, and never used by LIVE, discovery or AI. Operational database backups follow the backup-retention release gate in `docs/privacy/DATA_RETENTION_AND_DELETION.md`.

## Security and abuse

- **Authentication and authorization:** session authentication plus account-header matching, same as journals (T01/T02). Rows are selected only by the authenticated account ID; there is no path parameter to guess.
- **CSRF and origin:** the existing browser guard applies.
- **Rate limits:** 120 requests per minute per IP (existing guard), plus 20 writes per minute per account (`planning-write-account`), with saves and deletes sharing that budget.
- **Bounded work:** one row per account, a bounded document, 5-second transactions and a single-row lock.
- **Privacy (T12/T19):** plan text may contain home or work addresses, so upload is always explicit, owner-only and removable. Privacy copy states this.

## Tests

- **Domain and parser:** every validation rule, duplicate or unknown fields, number coercion, Unicode and size limits.
- **PostgreSQL:**
  - first save, compare-and-swap, stale version, replay, and mutation reuse with different content;
  - a concurrent first save;
  - delete (matching, mismatched, absent);
  - account-deletion cascade;
  - owner isolation.
- **HTTP:**
  - flag off returns 401 and keeps the 20 KiB limit;
  - unauthenticated and wrong account header return 401;
  - bad origin and missing CSRF return 403, wrong content type returns 415;
  - over-limit bodies return 413, with the 20 KiB limit still applying to other paths;
  - 429 at the rate limit, and the no-store header.
- **Proxy:** route allowlist, methods, flag off, and the larger body limit only for this path.
- **Transport:** response validation, the 409 and uncertain-outcome mapping, exact retry, abort, and account mismatch.
- **UI:** hidden without a flag or account, explicit check, replace confirmation, merge summary, conflict, uncertain retry, offline, and account change clearing the view.
- **Rendered QA:** Playwright against a synthetic API, at desktop and 360 px widths with large text.

## Acceptance criteria

1. With the flags off, nothing is rendered or reachable.
2. A signed-in traveller can save on one device and add the same plans and saved places on another, without losing local-only plans on either.
3. A replace never silently overwrites a newer account copy: a stale version returns 409, and the UI requires a new check.
4. An uncertain save can be retried exactly, without creating a second version.
5. Deleting the account deletes the copy, and one account can never read or change another account's copy.
6. All TypeScript and Java checks pass. Rendered states are inspected at small widths and with large text.
