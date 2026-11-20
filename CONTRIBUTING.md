# Contributing

## Ground rules
* **Privacy first.** Do not add features that collect message/call/notification content, keystrokes, location,
  recordings, or anything covert. Proposals that weaken consent or transparency will not be accepted.
* Every server endpoint must authorise through `AccessPolicy` (or an equivalent explicit check) and have a negative
  authorization test.
* Never commit secrets, keystores, `.env`, `keystore.properties` or `local.properties`.

## Workflow
1. Branch from `main`: `feature/<short-name>` or `fix/<short-name>`.
2. Keep changes focused; update docs (API, schema, permissions) in the same PR.
3. Run `./scripts/test-all.sh` locally; all checks must pass.
4. If dependencies change, regenerate verification metadata (see BUILD_GUIDE.md) and explain why in the PR.
5. Schema changes = new Flyway migration (`V<n>__description.sql`); never edit an applied migration.
6. Add an entry under *Unreleased* in CHANGELOG.md.

## Code style
* Kotlin official style (`kotlin.code.style=official`), 4-space indent, max ~140 columns.
* Android: MVVM — UI state as `StateFlow`, no business logic in composables; repositories return `AppResult`.
* Pure logic goes in `:domain` with JVM unit tests.
* Backend: controllers stay thin; business rules and authorization live in services.
* Prefer explicit error codes (`ApiException(code=...)`) and document new codes in `openapi/openapi.yaml`.

## Commit messages
Imperative mood, ≤ 72-char subject, body explains *why*. Reference issues where relevant.

## Testing expectations
* New backend endpoint: happy path, validation, authorization (other family → 404, wrong role → 403).
* New Android ViewModel: state transitions with fakes; new pure logic: unit tests in `:domain`.
* UI: at least one Compose test for new interactive components; accessibility (content descriptions) considered.
