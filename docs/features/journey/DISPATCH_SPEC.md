# Single-command journey dispatch

Dispatch one durable leased head at a time through an injected transport. Check the verified active account before claiming and immediately before sending. The HTTP transport must send X-Routiqo-Account; the server compares it with the authenticated cookie, so an account switch between client checks and the request cannot misattribute a command. The header is a consistency check, never an authentication mechanism.

Use fresh UUID leases, existing FIFO/backoff and atomic snapshot acknowledgement. Network/timeout/5xx/429 outcomes defer;401/403 block for session restoration;409 blocks for reconciliation;other4xx reject. Malformed successful responses defer and never remove work. Only actual validated success can save a snapshot and acknowledge. Persistence failures propagate and leave the durable lease for later recovery. No network call inside a database transaction. No automatic command discard.

This slice provides orchestration and a same-origin web transport, without mounting a background loop or exposing live journey controls. Native secure credential transport and web durable storage are still prerequisites for their dispatchers. Tests inject transport failures and account switches; no real user account or external network is used.
