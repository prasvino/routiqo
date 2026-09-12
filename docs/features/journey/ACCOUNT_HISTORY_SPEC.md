# Account journey history browsing

Use the existing authenticated GET /api/v1/journeys keyset endpoint to browse older journey records and open their completed-trip journals. This complements the bounded offline recent snapshot cache; it does not widen that cache, drain the entire history, change outbox state or claim complete commute summaries.

Fetch only after an explicit user action. Keep one page of at most20 records in component memory. Earlier journeys follows the server next cursor; Latest journeys resets to the first page. Failed reads preserve the current page for retry except authentication failures, which clear private results and request sign-in. Account changes and unmount abort requests and ignore stale responses. No automatic retries or requests on every render. A completed TRIP offers its journal; commutes and active records do not.

Transport sends the matching account header, same-origin cookies, no-store and redirect:error. Use a12second total abort timeout and stream-decode at most32KiB UTF-8. Validate canonical lifecycle records, unique IDs, strict descending (startedAt,id) ordering, and every result older than the supplied cursor. The next cursor must be null or equal the last record's key on a full20-record page. Reject incomplete or malformed cursors and invalid pages without partial rendering. The server remains responsible for owner authorization.

Use existing disclosure styling and simple rows with type, status and dates. Clearly label this as account history requiring a connection; existing offline summaries and queued actions remain separate. Do not expose IDs as user-facing copy. No provider calls or location data. Tests cover owner header/query/cancellation, malformed/duplicate/unordered/non-progressing pages, byte limits, empty pages, manual pagination, failure retry, account changes and trip-only journal entry.

Threats: T02 object/account isolation, T05 enumeration (own records only), T12 private in-memory history, T13 stale/reordered replies, T14 bounded pages/requests and T19 no new persistent copies. Root owns final review; real OAuth/device verification remains outside synthetic tests.
