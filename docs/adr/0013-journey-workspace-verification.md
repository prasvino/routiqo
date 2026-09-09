# ADR0013: Journey workspace verification and deletion retirement

Status: accepted,2026-09-09.

Use development-only @testing-library/react16.3.0 and jsdom26.1.0 for accessible component interaction tests in the web workspace. Tests mock only service/storage boundaries; independent IndexedDB tests verify atomic storage behavior. Vitest uses the installed Vite8 Oxc JSX transform. These checks complement real browser layout verification and do not claim native or live Google behavior.

After confirmed account deletion, replace local journey data with a retired marker under the opaque account UUID. Refuse subsequent reads/writes for that UUID so delayed responses cannot resurrect deleted data. Preserve only the marker until browser storage is cleared. Normal logout retains the partition. Backend deleted UUIDs are not reused.

Recent-history restoration is bounded to20 records per explicit request and cannot silently clear queued actions. Only a validated matching server result can reconcile a blocked head, in the same transaction as snapshot persistence. Broader history and unresolved conflicts remain future work.
