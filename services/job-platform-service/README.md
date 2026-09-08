# job-platform-service

Java 21 / Spring Boot 3.5.16 / Maven. Spring Boot skeleton only — no
entities, repositories, controllers, or Flyway migrations yet.

Owns Job Processing (`company`, `job`, `job_source`, `job_location`,
`skill`, `skill_alias`, `job_skill`, `raw_job_processing`), Matching
(`job_match`, `job_match_skill`), User/Resume (`app_user`, `resume` and
related tables, `user_job_preference`), and Applications (`saved_job`,
`passed_job`, `application`, `application_status_history`). Exposes the
REST API consumed by `frontend/`.

See `../../docs/architecture.md`, `../../docs/database-design.md`,
`../../docs/deduplication-design.md`, `../../docs/matching-design.md`, and
the root `CLAUDE.md` before adding code here.

## Package structure

Base package: `com.autoapplyjobs.platform`

| Package | Purpose |
|---|---|
| `user` | User/Resume module (`app_user`, resume tables, `user_job_preference`) |
| `company` | Company canonicalization (`company`) |
| `job` | Job Processing (`job`, `job_source`, `job_location`, `skill`, `skill_alias`, `job_skill`, `raw_job_processing`) |
| `resume` | Reserved for resume-specific logic — see the package's Javadoc; boundary with `user` not yet finalized |
| `matching` | Matching (`job_match`, `job_match_skill`) |
| `application` | Applications (`saved_job`, `passed_job`, `application`, `application_status_history`) |
| `search` | Read-side search/filter over canonical jobs |
| `common` | Shared cross-cutting types, kept small |
| `config` | Application-level Spring configuration |

## Building and running

```
./mvnw test    # Windows: mvnw.cmd test
./mvnw package # Windows: mvnw.cmd package
```

No PostgreSQL instance is required yet. `DataSourceAutoConfiguration`,
`HibernateJpaAutoConfiguration`, and `FlywayAutoConfiguration` are
temporarily excluded in `JobPlatformServiceApplication` — see its Javadoc.
This is removed once the "PostgreSQL Docker" and "Flyway configuration"
milestones land.
