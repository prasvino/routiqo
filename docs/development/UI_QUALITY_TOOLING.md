# UI quality tooling (Claude Code)

## Project skill

`.claude/skills/routiqo-ui-quality/SKILL.md` is the Routiqo-specific UI review and refinement skill. Claude Code discovers it automatically when this repository is the working project. It keeps existing tokens, journey utility, offline recovery and privacy gates intact.

## Impeccable (optional, not installed)

The earlier Codex setup vendored Impeccable 4.3.1 under `.agents/skills` and ran Codex hooks from a machine-local `.codex/hooks.json`. That Codex-only install has been removed. To use Impeccable with Claude Code, install it project-scoped for the Claude provider:

```sh
npx --yes impeccable install -y --providers=claude --scope=project --no-hooks
```

- `--no-hooks` skips the automatic PostToolUse/Stop design passes. Leave the flag off only after reviewing the hook commands and launcher it writes into `.claude/settings.json`. Hooks are advisory tooling, not product or security authority.
- The launcher runs a platform engine binary, and may download a SHA-256-checked copy on first use. Keep generated binaries and caches out of commits (`.gitignore` already covers the Impeccable cache paths).
- Use at most one general design skill alongside `routiqo-ui-quality` (UI system §22).

## Browser QA

Cloud sessions have Chromium pre-installed for Playwright (`PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers`). Do not run `playwright install`. Android emulator checks need a local Android SDK and are recorded as pending when it is not available.
