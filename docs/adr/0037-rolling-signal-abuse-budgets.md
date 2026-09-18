# ADR 0037: Durable rolling signal acceptance budgets

Date: 2026-09-18
Status: accepted for implementation

The existing fixed-minute grant/acceptance and HTTP request budgets remain.
Every new signal acceptance also requires fewer than 20 preceding-hour charges
and no charge for the same actor/anchor/category in the preceding 60 seconds.
Exact boundary instants release the old charge. These conservative values adopt
the first-release proposal as enforceable private-ingestion limits, not approval
of publication, anonymity or independent evidence.

Reserve charges within the existing account/owned-journey transaction. Store at
most 20 private slots per actor independently of receipt/grant/journey lifetimes.
Receipt cleanup, withdrawal, supersession and journey rotation cannot free quota.
Reuse only expired slots; retained exact replay does not reserve again. Future
stored timestamps fail closed. Account deletion cascades the ledger.

Extend the existing bounded default-off maintenance job with an independent ledger
batch. Logical expiry is one hour; physical removal may lag. No precise location,
device fingerprint, IP history, provider call, public endpoint or runtime activation
is introduced. Anti-Sybil eligibility remains a separate unsatisfied gate.

Acceptance and rollback requirements: SIGNAL_ABUSE_BUDGET_SPEC.md. V13 is tested
only in disposable databases. A production upgrade needs migration verification;
do not roll back enforcement code while leaving public writes enabled.

V13 starts with an empty ledger. Retained receipts cannot safely reconstruct all
prior charges because short-retention receipts may already be gone. For any
environment with prior signal acceptance, stop ALL old and new acceptance writers
and wait at least one full hour after the last pre-V13 acceptance before enabling
the enforcing version. Migrate and verify while writes remain stopped. Never run
mixed old/new acceptance writers during rollout. A fresh database with no prior
acceptance needs no drain. Rollback also keeps writes disabled until a safe
enforcing version and quota history are restored. Migration alone does not enforce
this operational cutover; default-off transport remains off in this run.
