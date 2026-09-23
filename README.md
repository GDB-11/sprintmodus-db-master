# sprintmodus-db-master

Flyway migrations for the Sprintmodus **master (platform)** database. This repo has no application code: it builds a
plain jar (`com.sprintmodus:db-master`) whose only content is the SQL under `src/main/resources/db/migration/master`.

**Consumer:** `auth-service`, the only service with access to the master database. It depends on this jar and runs the scripts at startup (`spring.flyway.locations=classpath:db/migration/master`).

## Scope

The master database holds **tenant-level data only**: organizations, their subscriptions and the platform audit log. It has no user accounts. Every user lives in their tenant's database (`sprintmodus-db-tenant`) and authenticates against it; the master DB is consulted at login to resolve the organization and check its subscription.

## Rules

- **Never edit a migration that has been merged.** Flyway checksums applied scripts; add a new `V<next>` file instead.
- File names: `V<major>.<minor>__<Description_with_underscores>.sql`, one concern per file, strictly increasing.
- No `CREATE DATABASE` / `USE` in scripts. Flyway runs against the database it is given.
- No `IF NOT EXISTS`. A migration that only works when it is a no-op hides schema drift.
- Utf8mb4 / `utf8mb4_unicode_ci`, InnoDB, PascalCase singular table names, `*Code` = UUID exposed to clients.
- UUID columns (`TenantId`, `*Code`) are `BINARY(16) NOT NULL` with a random **v4** default, spelled out as:

  ```sql
  DEFAULT ((RANDOM_BYTES(16) & X'FFFFFFFFFFFF0FFF3FFFFFFFFFFFFFFF') | X'00000000000040008000000000000000')
  ```

  MySQL's `UUID()` is version 1 (time + MAC), which is not opaque, and column defaults cannot call stored functions, so the default masks the version/variant bits of one `RANDOM_BYTES(16)` call. Read with `BIN_TO_UUID()`, write with `UUID_TO_BIN()`. Every migration test checks that the defaults are valid, distinct v4 values.
- **MySQL only.** MariaDB is not allowed on this project, so no MariaDB-only features (native `UUID` type, `UUID_v7()`).
- Every change must pass the tests below before it is merged.

## Tests

```bash
./mvnw clean verify
```

The test starts a throwaway `mysql:9.7` container (Docker required), applies every migration to an empty
database with the MySQL Connector/J driver, and checks the resulting schema and its constraints.

## Status

V1.0 - V1.2 create `Organization`, `OrganizationSubscription` and `AuditLog` (Phase 2). `Organization` has two identifiers: `TenantId` (UUID, used in the JWT and to name the tenant DB) and `OrganizationCode` (short, easy to remember, chosen by the owner, typed at login).
