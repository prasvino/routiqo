# Routiqo Codex Orchestration Strategy

Use this strategy for substantial Codex implementation tasks to reduce token usage and repeated context processing without reducing engineering quality.

The goal is:

**Use the strongest model where judgment matters and cheaper models where bounded execution is sufficient.**

Do not spawn agents merely because roles are available.

Delegate only when delegation provides a clear benefit such as:

- reducing repeated repository exploration;
- parallelizing independent work;
- isolating implementation from architectural reasoning;
- performing focused external research;
- or obtaining an independent review of a high-risk change.

The root agent remains responsible for the final integrated result.

This file is the primary model-routing and orchestration policy. Keep detailed
requirements in their canonical documents:

- `CODE_REVIEW.md` — review criteria, finding severity, adversarial review, and review output;
- `SECURITY.md` — security/privacy invariants, vulnerability handling, and incident response;
- `THREAT_MODEL.md` — threat actors, trust boundaries, abuse cases, and cyberattack scenarios.

Do not duplicate those detailed rules here. Load the relevant document when a
task touches its boundary.

---

# 1. Default orchestration model

Use the following conceptual hierarchy:

```text
GPT-6 Astra · medium
ROOT / ORCHESTRATOR
        |
        | delegate only when useful
        |
        +-------------------+-------------------+
        |                   |                   |
     Explorer             Worker            Researcher
   Luna · medium         Sol · high       Luna · medium
        |                   |                   |
 bounded repository     implementation      focused lookup /
 investigation          + targeted tests    external research
        |                   |                   |
        +-------------------+-------------------+
                            |
                    GPT-6 Astra · medium
                     integrate + verify
                            |
                            | only when needed
                            v
                    GPT-6 Astra · high
                    independent review
```

This is a routing model, not a mandatory workflow.

Most tasks should use fewer agents.

---

# 2. Root Astra responsibilities

The root/orchestrator owns:

- understanding the user's request;
- determining task scope;
- identifying relevant Routiqo documentation;
- preserving product intent;
- preserving architecture;
- preserving security/privacy invariants;
- identifying applicable threats and abuse cases;
- deciding whether delegation is useful;
- decomposing work;
- integrating delegated results;
- inspecting the final implementation;
- deciding what validation is required;
- running or evaluating final verification;
- determining whether ADR/documentation updates are required;
- reporting what is verified and unverified.

The root agent must retain responsibility for cross-cutting judgment.

Do not delegate final architectural responsibility.

When security, privacy, safety, or abuse risk is relevant, the root must apply
`SECURITY.md`, `THREAT_MODEL.md`, and the review gates in `CODE_REVIEW.md`.

---

# 3. Do not automatically spawn every role

Use the smallest useful agent tree.

Examples:

## Small/local change

```text
Astra medium
    ↓
implement directly
    ↓
targeted verification
```

Examples:

- small bug fix;
- simple styling change;
- test correction;
- narrow refactor;
- straightforward configuration change.

Do not create subagents unnecessarily.

---

## Scoped implementation

```text
Astra medium
    ↓
Sol high worker
    ↓
Astra medium integration + verification
```

Use when the root already understands the relevant code and requirements.

---

## Unfamiliar repository area

```text
Astra medium
    ↓
Luna explorer
    ↓
Sol worker
    ↓
Astra medium integration + verification
```

Use when repository exploration would otherwise consume substantial root-agent context.

---

## External dependency/API uncertainty

```text
Astra medium
    ↓
Luna researcher
    ↓
Sol worker if implementation is required
    ↓
Astra medium integration + verification
```

Use when current documentation, provider behavior, dependency compatibility, or API details must be investigated.

---

## High-risk architectural/security/privacy change

```text
Astra medium
    ↓
bounded exploration/research if useful
    ↓
Sol high implementation
    ↓
Astra medium integration + verification
    ↓
Astra high independent review
```

Use the independent review only when justified.

The reviewer must be given the applicable security invariants and threat cases.
Scanner output alone is not an independent security review.

---

# 4. Explorer role

The explorer performs bounded repository investigation.

Typical responsibilities:

- locate relevant files;
- trace code paths;
- identify tests;
- identify relevant feature specifications;
- identify relevant architecture documents;
- identify relevant ADRs;
- identify established patterns;
- identify likely integration points.

The explorer must not perform an unbounded repository survey.

Give it a narrow question.

Good example:

> Find the files implementing route planning, routing provider requests, related tests, and relevant ADRs. Return paths and concise findings only.

Bad example:

> Understand the entire Routiqo repository.

---

# 5. Explorer output must be concise

Explorers should not dump entire source files back to the root.

Prefer output such as:

```text
Relevant files:
- apps/web/.../RoutePlanner.tsx
- packages/api/.../routes.ts
- docs/features/routes/ROUTE_SPEC.md
- docs/adr/0014...
- docs/adr/0016...

Findings:
1. Route requests currently pass through core-api.
2. Frontend route/search requests use the authenticated backend; renderer asset requests follow ADR 0021 and the explicitly configured tile/style origin.
3. Browser location is one-time only.
4. Existing tests cover X and Y.
5. ADR 0016 prohibits Z.
```

The root can open exact files when required.

Avoid duplicating large source/document contents into the parent context.

---

# 6. Worker role

The worker performs scoped implementation.

For substantial, well-scoped coding work, prefer the worker over having Astra perform routine implementation itself. Astra should primarily scope, integrate, and verify unless the task is small enough to handle directly or requires cross-cutting architectural/security/privacy judgment.

Default worker:

**GPT-5.6 Sol · high**

Worker responsibilities may include:

- implementing clearly defined changes;
- updating affected tests;
- running targeted validation;
- fixing regressions caused by the implementation;
- producing a concise implementation summary.

Workers must receive:

- a bounded task;
- required files/context;
- relevant invariants;
- clear acceptance criteria.

Do not ask the worker to rediscover the entire product architecture if the root/explorer has already scoped the work.

---

# 7. Worker boundaries

The worker should not independently redefine:

- Routiqo product behavior;
- major architecture;
- privacy policy;
- security boundaries;
- public API contracts;
- data-retention policy;
- anti-stalking semantics;
- authentication/authorization architecture;
- multi-replica correctness strategy.

If implementation exposes an architectural conflict or ambiguity, return it to the root rather than silently inventing a new direction.

---

# 8. Researcher role

Use a researcher for narrow external questions.

Typical examples:

- current provider/API behavior;
- dependency compatibility;
- framework-specific behavior;
- provider limits;
- protocol semantics;
- standards/documentation lookup.

Default:

**Luna · medium**

Do not use research agents for questions that are already answered by authoritative Routiqo documentation.

The researcher should return concise findings and source references, not broad essays.

---

# 9. Reasoning effort defaults

Do not automatically use maximum reasoning.

Recommended defaults:

| Role | Default reasoning |
|---|---|
| Root/orchestrator | Astra medium |
| Explorer | Luna medium |
| Worker | Sol high |
| Researcher | Luna medium |
| Integration/final verification | Astra medium |
| Independent reviewer | Astra high |

Escalate reasoning only when the current level cannot resolve the task confidently.

Avoid `max` reasoning for trivial repository searches or simple documentation lookups.

---

# 10. Root must stay involved in high-risk areas

The root Astra must remain directly involved whenever the task touches:

- authentication;
- authorization;
- session security;
- privacy;
- precise location;
- presence;
- Ghost Mode;
- anti-stalking;
- discoverability;
- home/work protection;
- schema design;
- major persistence changes;
- public API contracts;
- concurrency;
- idempotency;
- offline guarantees;
- realtime security;
- multi-replica correctness;
- retention/deletion;
- vulnerability remediation;
- suspected cyberattack or active exploitation;
- moderation and abuse-control boundaries;
- architecture boundaries.

These decisions must not be delegated entirely to cheaper agents.

Delegation may assist investigation or implementation, but root Astra owns the design and final verification.

---

# 11. Context efficiency rules

Every delegated agent should receive only the context required for its task.

Prefer:

- relevant feature specification;
- relevant architecture file;
- relevant privacy/security documentation;
- applicable ADRs;
- exact source files or file paths.

Avoid sending:

- all documentation;
- all feature specifications;
- unrelated ADRs;
- entire repository summaries;
- giant context dumps.

Follow Routiqo's progressive-disclosure rules.

---

# 12. Reuse findings instead of repeating exploration

Do not make multiple agents independently rediscover the same repository structure unless independent verification is specifically required.

If the explorer has already identified:

- relevant files;
- tests;
- ADRs;
- documentation;

pass those findings to the worker.

Do not ask the worker to repeat an expensive broad search.

---

# 13. Parallelism rules

Parallelize only independent work.

Good examples:

```text
Explorer A → backend route implementation
Explorer B → frontend route UI
Researcher → current selected routing/rendering provider API detail
```

when those investigations do not depend on one another.

Avoid parallel agents modifying overlapping files or making competing architecture decisions.

The root must merge and reconcile all parallel findings.

---

# 14. Final integration is mandatory

Subagent output is not automatically accepted as the final implementation.

After delegated work completes, Astra must:

1. inspect the relevant diff;
2. compare it against the original requirements;
3. verify consistency with architecture and ADRs;
4. check privacy/security implications and applicable threat cases;
5. inspect affected tests;
6. run or review appropriate validation;
7. identify regressions or unnecessary complexity;
8. request/fix corrections if needed;
9. update documentation/ADR status if required;
10. report actual verification performed.

Apply the detailed disposition and reporting rules in `CODE_REVIEW.md`.

The root owns the integrated result.

---

# 15. Verification must be proportional to the change

Do not automatically run every test suite for every modification.

Select validation according to risk and scope.

Examples:

## Small frontend styling change

Run as appropriate:

- formatting;
- linting;
- relevant component tests;
- TypeScript checks.

Do not automatically run every backend integration suite unless there is a dependency.

---

## Route API change

Run as appropriate:

- route unit tests;
- route integration tests;
- OpenAPI drift verification;
- affected backend tests;
- relevant frontend/API contract checks.

---

## Presence/privacy change

Use broader validation:

- presence tests;
- privacy tests;
- authorization tests;
- realtime tests if applicable;
- API integration tests;
- Ghost Mode behavior;
- stale-presence behavior;
- multi-user isolation;
- multi-replica correctness where applicable.

---

# 16. High-risk independent review

Use:

**GPT-6 Astra · high**

as an independent review stage only when justified.

Examples:

- authentication redesign;
- authorization changes;
- location/privacy semantics;
- presence/discoverability changes;
- anti-stalking mechanisms;
- schema or migration with significant data risk;
- major architectural change;
- concurrency or distributed correctness;
- large cross-domain change;
- critical release candidate.

The reviewer should challenge:

- assumptions;
- security;
- privacy;
- race conditions;
- authorization boundaries;
- missing tests;
- architecture drift;
- unnecessary complexity;
- failure modes.

Do not use an expensive independent reviewer for routine changes.

For security-sensitive work, the review must consider malicious authenticated
users, alternate delivery channels, bypasses, enumeration, replay, resource
exhaustion, supply-chain compromise, and attacker persistence where applicable.
Use `THREAT_MODEL.md` as the baseline rather than relying only on vulnerability
scanner results.

---

# 17. Preserve agent independence during critical review

When performing independent review, provide:

- task requirements;
- relevant architecture/security/privacy constraints;
- final diff or implementation;
- test results.

Avoid biasing the reviewer with statements such as:

> The implementation looks correct.

Ask instead:

> Independently review this implementation for correctness, security, privacy, architecture consistency, edge cases, and missing tests. Identify concrete issues only.

---

# 18. Prefer summaries across agent boundaries

Agent-to-agent communication should optimize for useful information density.

Prefer:

- file paths;
- concise findings;
- decisions;
- risks;
- test results;
- unresolved questions.

Avoid:

- copied source files;
- repeated documentation;
- verbose narratives;
- duplicate reasoning.

Exact source can be reopened when necessary.

---

# 19. Stop delegating when delegation costs more than direct work

Before spawning an agent, ask:

> Will delegation meaningfully reduce context use, enable parallel work, provide specialized investigation, or improve independent validation?

If not, perform the task directly.

Delegation itself has overhead.

The goal is not maximum agent count.

The goal is **minimum total effort for production-quality output**.

---

# 20. Avoid delegation loops

Do not create chains such as:

```text
Astra
  ↓
Explorer
  ↓
Another explorer
  ↓
Researcher
  ↓
Another researcher
```

unless the task genuinely requires it.

Prefer shallow orchestration.

The root should normally delegate directly and integrate directly.

---

# 21. Preserve Routiqo's established implementation baseline

All agents must treat Routiqo as an existing production-quality codebase, not a greenfield prototype.

Preserve established:

- domain boundaries;
- privacy model;
- security model;
- offline model;
- API conventions;
- persistence patterns;
- session architecture;
- idempotency;
- owner isolation;
- rate limiting;
- OpenAPI discipline;
- testing conventions.

Do not redesign verified functionality merely because another implementation is possible.

---

# 22. Special rule for privacy-sensitive social/location work

For any feature involving:

- presence;
- route communities;
- route rooms;
- nearby users;
- live updates;
- stranger discovery;
- location sharing;

the root Astra must explicitly verify:

- raw stranger GPS is not exposed;
- home/work endpoints are protected;
- presence is privacy-transformed server-side;
- discoverability respects consent;
- Ghost Mode semantics remain correct;
- stale presence is removed;
- authorization is enforced;
- enumerable tracking is prevented;
- multi-replica behavior is correct.

Do not delegate these final checks completely to a worker.

---

# 23. Completion criteria

For delegated tasks, Codex should not finish merely because agents returned successfully.

The root must confirm:

- requested behavior was implemented;
- relevant tests pass;
- applicable build/type/lint checks pass;
- architecture remains consistent;
- privacy/security constraints remain intact;
- documentation is updated if required;
- ADRs are updated if architecture changed;
- no unnecessary unrelated files changed;
- final diff was reviewed;
- unresolved risks are explicitly reported.

When applicable, the root must also confirm:

- `SECURITY.md` invariants remain intact;
- relevant `THREAT_MODEL.md` threats and abuse cases were addressed;
- Critical and High review findings are resolved;
- vulnerabilities and suspected attacks follow the defined private handling and
  incident process.

---

# 24. Cost/quality principle

Use this priority order:

1. preserve correctness;
2. preserve privacy/security;
3. preserve architecture;
4. preserve maintainability;
5. avoid unnecessary context;
6. avoid unnecessary expensive reasoning;
7. avoid unnecessary agents.

Never reduce reasoning/model strength merely to save tokens when doing so creates meaningful correctness or security risk.

Instead, save tokens primarily through:

- narrow task scopes;
- progressive documentation loading;
- bounded repository exploration;
- concise agent outputs;
- avoiding repeated discovery;
- proportional testing;
- conditional delegation;
- conditional high-level review.

---

# 25. Usage-efficiency policy

Optimize model usage without reducing correctness, verification quality, privacy, security, or architectural discipline.

Default model responsibilities:

- **Astra medium**
  - task decomposition;
  - architecture decisions;
  - product interpretation;
  - privacy/security reasoning;
  - cross-domain judgment;
  - integration;
  - final verification.

- **Sol high**
  - substantial scoped implementation;
  - frontend/backend coding;
  - refactoring;
  - test implementation;
  - targeted build/test/fix loops.

- **Luna medium**
  - bounded repository exploration;
  - file discovery;
  - narrow dependency tracing;
  - focused external research.

Escalate Luna above medium only when bounded investigation is genuinely difficult or ambiguous.

Use Astra high only when justified by risk, especially for:

- security-critical changes;
- authentication/authorization architecture;
- privacy/location/presence changes;
- anti-stalking behavior;
- major architecture decisions;
- concurrency/distributed correctness;
- significant schema/data-risk changes;
- critical independent review.

Do not use Astra as the default implementation model for routine coding when Sol can safely execute a well-scoped implementation.

For substantial implementation, prefer the operating pattern:

```text
Astra medium
    ↓
scope + constraints + acceptance criteria
    ↓
Sol high
    ↓
implementation + targeted tests/fixes
    ↓
Astra medium
    ↓
integration + final verification
```

Use Luna before Sol only when repository exploration or external research is actually needed.

When usage limits or credits are becoming constrained:

- preserve required security/privacy review;
- preserve relevant testing and verification;
- preserve architecture checks;
- move bounded exploration toward Luna;
- move scoped implementation toward Sol;
- reduce repeated repository inspection;
- reuse previous findings;
- avoid duplicate agents;
- avoid broad context reloads;
- keep orchestration shallow;
- avoid Astra high unless the risk level justifies it.

Never save tokens by skipping necessary correctness, privacy, security, architecture, or validation work.

Token efficiency should come from better model routing, progressive context loading, bounded delegation, concise outputs, and avoiding duplicated work.

---

# 26. Default decision rule

Before each substantial task:

```text
Can Astra solve this directly with limited context?
    |
    +-- yes → do it directly
    |
    +-- no / expensive exploration needed
          |
          +-- repository uncertainty → Explorer
          |
          +-- external uncertainty → Researcher
          |
          +-- substantial scoped coding → Worker
          |
          +-- high-risk final result → Astra high review
```

Do not spawn roles that do not contribute materially.

---

# Final directive

Use orchestration to reduce duplicated reasoning, irrelevant context, and unnecessary expensive model usage, not as an end in itself.

The preferred operating model for substantial Routiqo work is:

> **Astra scopes → Luna explores/researches when needed → Sol implements → Astra integrates and verifies → Astra high reviews only when risk warrants it.**

Astra remains responsible for:

- decomposition;
- architecture;
- product intent;
- security/privacy invariants;
- cross-domain judgment;
- integration;
- final verification.

Sol should perform most substantial scoped implementation and targeted test/fix work.

Luna should perform most bounded repository exploration and focused external research.

Do not spawn Luna when Astra already has sufficient scope.

Do not use Astra for routine implementation when Sol can safely execute the scoped work.

Keep agent trees shallow.

Keep delegated tasks narrow.

Keep outputs concise.

Reuse investigation results.

Load documentation progressively.

Run tests proportional to risk.

Escalate reasoning only when necessary.

Use Astra-high independent review only for high-risk work.

When usage becomes constrained, shift bounded work toward Luna/Sol rather than weakening security, privacy, testing, architecture checks, or verification.

The desired outcome is:

**lower token usage, longer effective Codex capacity, and faster implementation while maintaining or improving Routiqo's production code quality, security, privacy, architecture, maintainability, and verification discipline.**
