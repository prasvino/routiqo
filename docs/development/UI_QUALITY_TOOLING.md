# Project-local UI quality tooling

Installed on 2026-09-18 from `D:\Pras\routiqo` with
`npx --yes impeccable install --providers=codex --scope=project`.
Skill version: 4.3.1. Local Windows x64 engine: 0.1.5.
No global Impeccable installation was requested or performed; npm's normal download
cache is separate from the project-scoped skills.

Use `.agents/skills/routiqo-ui-quality/SKILL.md` for Routiqo-specific checks.
Prefer `$audit`, `$critique`, `$polish`, `$distill`, or `$impeccable <mode>`.
The four shortcuts are pinned locally under `.agents/skills`. The custom skill
keeps existing tokens, journey utility, offline recovery and privacy gates intact.
No app interface was changed as part of this installation.

## Hook review

Installer-created `.codex/hooks.json` remains machine-local under the existing
gitignore. It contains PostToolUse (`Edit|Write|apply_patch`, 5-second timeout)
and Stop (30-second timeout) hooks. Both invoke the installed Impeccable launcher
with `hook`; Windows uses the `.cmd` launcher. The second hook performs an
end-of-turn design pass, not just a per-edit check.

Reviewed the JSON and Windows launcher before trusting the two definitions in
Codex's interactive `/hooks` screen. That screen reported both active, with no
remaining review entries. This is a command/launcher review, not an audit of the
compiled engine. The installed executable answered `impeccable-engine 0.1.5`.
SHA-256: `477E544FC8880A5E82E427490CB9A5F5ADAB480C0E02966D33FD50772B531C71`.

The launcher prefers the adjacent project binary, but supports environment,
user-cache/PATH fallbacks and downloading a SHA-256-checked engine if needed.
Do not describe hook execution as unconditionally network-free. Reinspect changed
hook definitions and launchers before renewed trust; never bypass hook trust.
Keep generated binary/cache and per-developer settings out of commits.

## Discovery

Open Codex with this repository as its working project. Local skills are discovered
from `.agents/skills`; a task rooted in `F:\Interior` does not gain Routiqo's local
skills merely because a command used Routiqo as its working directory.
Check `/skills` for Impeccable, Routiqo UI Quality and the four shortcuts. Restart
Codex if changes do not appear. CLI discovery and a Desktop application restart
are separate checks; do not claim one proves the other.

References: [Codex skills](https://learn.chatgpt.com/docs/build-skills) and
[hook review/trust](https://learn.chatgpt.com/docs/hooks).

Verification completed: a fresh Codex CLI 0.154.0-alpha.6.2 session rooted at
D:\Pras\routiqo displayed Impeccable, Routiqo UI Quality, audit, critique, polish
and distill in the `/skills` picker. `/hooks` showed PostToolUse and Stop active
with no pending review. Skill frontmatter and UI metadata parsed successfully
using the repository's YAML parser; hook JSON parsed successfully. The bundled
Python validator could not run because its Python environment lacked PyYAML.
Git diff checks passed. No Desktop application restart was performed or verified;
that requires reopening the Desktop app on the Routiqo project.
