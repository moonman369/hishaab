# hishaab-backend — instructions for Claude Code

This is the Spring Boot backend of Hishaab.ai (personal spend tracker built from bank SMS and email).

## Read before any work
1. `docs/hishaab-backend-SPEC.md` — what to build, rules, acceptance criteria, status tracker. **Follow its §0 workflow for every task.**
2. `../docs/Hishaab-ai-HLD.md` — the design and the reasons behind it.

## Commands
- Build + all tests: `./mvnw verify`
- Run locally: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- Integration tests need Docker running (Testcontainers).

## Non-negotiables (full list in SPEC §0.2)
- Money is `long` paise. Never floating point.
- Never edit or delete `raw_message` content. Schema changes only via new Flyway migrations.
- Modules call each other only through their public package, never `internal`.
- Tests first, named after acceptance IDs (`AC_011_03_...`). Never weaken a test to pass.
- When unsure about anything affecting totals or dedup: stop and ask, and make it a review item in the design rather than a guess.
- No new dependencies without asking. No secrets in code.

## After finishing a spec
Update the Status tracker (§3), tick the spec's tasks, and add any decision to the Decision log (§6) in `docs/hishaab-backend-SPEC.md`.
