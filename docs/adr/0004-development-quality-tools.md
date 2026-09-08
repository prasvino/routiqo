# 0004 — Reproducible formatting and secret scanning

Use pinned Prettier 3.9.6 for application/shared code, tests, scripts and CI configuration. Original supplied requirements and generated API schema are excluded from formatting. Contract checking generates to a temporary file and does not rewrite the checked-in schema.

Use the open-source Gitleaks CLI 8.30.1 via a digest-pinned Docker image. `pnpm secrets:check` scans a temporary snapshot of Git-visible source files, including untracked source, with network disabled and redacted output. Dependency/build output is excluded by existing Git ignores. It does not require a SaaS account or send source outside the machine. CI runs the same working-tree check; historical Git scanning should be added once a remote and commit history exist. This is a detection gate, not proof that arbitrary secrets cannot exist.

Sources: [Gitleaks CLI](https://github.com/gitleaks/gitleaks), [release 8.30.1](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1). The downloaded image resolved to sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f. Prettier was resolved through the npm registry. These are development tools, with no application runtime data flow.
