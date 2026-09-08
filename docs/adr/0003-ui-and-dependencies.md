# ADR 0003: Platform UI and selected libraries
Status: accepted, 2026-09-06.
Share design tokens/domain helpers; implement web UI with semantic HTML/CSS and lucide-react, mobile UI with React Native and Expo SQLite. Do not introduce a universal UI framework.
Next.js provides routing/builds. Vitest/ESLint/TypeScript cover TypeScript quality. Spring Security defaults fail closed. ArchUnit enforces Java domain independence. Gradle wrapper pins the Java build.
Expo SDK 55 is a deliberately selected stable baseline; use its recommended React/native package versions. Upgrade via a compatibility review.
Photography is illustrative destination atmosphere, not a claim of exact pictured location. Curated destination text is seed content. Image sources/licenses belong in docs/design/ASSETS.md.
No map, AI or external identity provider is initialized in this phase.

