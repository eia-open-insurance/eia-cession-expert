# eia-cession-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-cession-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Cession
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_cession (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Cession persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Direct-book ceded reinsurance contracts, cession rules, FS-RI hand-off, and recoverables.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Implemented slice

Spring Boot 3.4.0, Java 22, Gradle Kotlin DSL, port `8091`, PostgreSQL database `eia_cession`.
`schema.sql` creates the `cession` table with its book, amount, and status check constraints plus
the FS-RI hand-off columns (`fsri_handoff_id`, `fsri_status`, `fsri_handed_off_at`,
`fsri_last_error`). The `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` statements expand an existing
database in place.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/internal/v1/cessions?tenantId={tenantId}` | List cessions |
| `GET` | `/internal/v1/cessions/{id}?tenantId={tenantId}` | Read one cession |
| `POST` | `/internal/v1/cessions` | Persist a cession in `OPEN` |
| `PUT` | `/internal/v1/cessions/{id}/status?tenantId={tenantId}` | Apply an optimistic, lifecycle-checked status change |
| `POST` | `/internal/v1/cessions/{id}/handoff?tenantId={tenantId}` | Record the FS-RI acknowledgement and move to `HANDED_OFF` |
| `POST` | `/internal/v1/cessions/{id}/handoff-failure?tenantId={tenantId}` | Record a failed FS-RI attempt, keeping the case retryable |

Invariants enforced here: `business_book` is fixed to `DIRECT`; `ceded_premium` and `ceded_loss`
are non-negative; the case lifecycle is `OPEN → RULE_APPLIED → HANDOFF_PENDING → HANDED_OFF →
CONFIRMED`, with `REJECTED` and `CANCELLED` branches (SPEC 11.9.3). `HANDOFF_PENDING` and
`HANDED_OFF` are refused by the plain status endpoint (`HANDOFF_REQUIRED`); they are written only
by the hand-off endpoint, in one optimistic UPDATE together with the FS-RI reference, status, and
timestamp. A failed attempt records `fsri_status = FAILED` and `fsri_last_error` without changing
the lifecycle status or consuming a version, so the retry reuses the same transition version and
idempotency key. Errors are structured `{code, message, details}` responses.

## Local run

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
$env:PGPASSWORD='postgres'
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -U postgres -h localhost -c "CREATE DATABASE eia_cession;"
.\gradlew.bat build
.\gradlew.bat bootRun
```

Health: `http://localhost:8091/actuator/health`.

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
