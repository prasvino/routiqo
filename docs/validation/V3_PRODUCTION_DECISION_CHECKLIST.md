# V3 community traffic summary — production decision checklist

Status: V3 implementation and staging evaluation authorized; **production activation not approved**. This checklist separates engineering evidence from acceptance of ADR 0055's different privacy contract. The existing ADR 0053/0054 code/docs remain preserved and disconnected.

- [ ] Owner reviews the completed staging release packet and explicitly accepts the V3 disclosure: participation may be inferred; accepted Share is only for consideration; withdrawal after the publication snapshot may be too late; authorized safety suppression and saved outside copies remain possible. This is not person-level differential privacy.
- [ ] Product/privacy and security review approve the exact Share, Stop, Ghost Mode, deletion, blocking and moderation wording, plus purpose/versioned consent and no migration of V18/V22 records.
- [ ] Real authenticated staging flow passes Share/Stop/recovery, publisher, active-journey reader, report/moderation and cleanup with reviewed regional catalog, Google OAuth, separate admin authentication and configuration. Confirm all production V3 flags remain disabled until approval.
- [ ] PostgreSQL concurrency/failover and HTTP security tests pass for duplicate/debit, snapshot boundaries, two publishers, rollback/retry, authority withdrawal, guessed references, block/reader policy, expiry, suppression, cleanup and restore without resurrection.
- [ ] Browser/device QA passes active-journey consent, uncertain write, fresh-device recovery, offline/reconnect, account switching, large text, keyboard/screen reader, narrow screens and reduced motion. No V3 automatic submission or durable offline public feed.
- [ ] Consented Chennai/OMR pilot data and independent road-condition ground truth establish output coverage, age, wrong-value/false-reassurance rate, repeated-commuter cap behavior, collusion/abuse risk and truthful empty states. [Synthetic exploration](V3_COMMUNITY_UTILITY_2026-09-23.md) does not close this item.
- [ ] Operations approve bounded candidate/projection/debit/report/audit and backup retention, cleanup monitoring, publisher deadlines, suppression authority, incident response and rollback/failover runbook. No expired or suppressed projection can reappear.

If any decision remains open, leave the production flags off and retain provider-sourced alerts as the separately gated LIVE option. Record approval evidence and flag changes here before production exposure.
