# CLAUDE.md

Guidance for AI assistants (and contributors) working in this repository.

## Project Goal

AutoApplyJobs is a job aggregation application. It collects job postings from
multiple permitted sources, removes duplicate postings, shows unique job
opportunities to users, and scores each job against the user's resume —
combined with save/pass tracking and full application tracking.

## Part One Scope (current phase)

- Collect jobs from multiple permitted sources
- Normalize job data into a canonical schema
- Deduplicate postings into unique canonical jobs
- Maintain a base/default resume per user
- Compute a job match percentage between a resume and each job
- Search and filter jobs
- Save / pass jobs
- Mark jobs as applied, recording the exact resume version used
- Track applications and their status history

## Explicitly Excluded (Part Two — not this phase)

- Automatic job applications
- Automatic screening-question answers
- Recruiter messaging
- Interview automation
- Automatic follow-ups

## Technology Stack

- **Backend:** Java / Spring Boot — two services: `job-platform-service`, `job-ingestion-service`
- **Frontend:** React
- **Database:** PostgreSQL from day one, local dev through production — never SQLite
- **API style:** REST only — no GraphQL
- **Messaging (target, introduced incrementally):** Kafka — async processing is designed around events from the start, even before Kafka is actually wired in
- **IDs:** UUID everywhere
- **Timestamps:** `TIMESTAMPTZ` (UTC-safe), never naive timestamps
- **Money / salary:** `NUMERIC` (BigDecimal-compatible), never floating point
- **Physical DB naming:** snake_case for every table and column

## Architecture Decisions

- Flow: `External Source → RawJobPosting → Normalize → Deduplicate → canonical Job → JobSource → Resume Matching → Search/API → Frontend`
- Two backend services split along data ownership:
  - **`job-ingestion-service`** owns `raw_job_posting` — pulls from sources, writes raw payloads only, never modifies them afterward
  - **`job-platform-service`** owns everything else: Job Processing (`company`, `job`, `job_source`, `job_location`, `skill`, `skill_alias`, `job_skill`, `raw_job_processing`), Matching (`job_match`, `job_match_skill`), User (`app_user`, `user_job_preference`), Resume (`resume`, `resume_profile`, `resume_skill`, `resume_experience`, `resume_education`), Applications — and exposes the REST API to the frontend
- Event-oriented seams are designed from day one — the event contracts (`job.canonical.created`, `job.canonical.updated`, `resume.processed`, `resume.default.changed`) are fixed even though Kafka isn't wired up yet. The two services are **not** expected to call each other in-process before then: `job-platform-service` can be developed and tested initially against fixtures/seed data rather than a live ingestion feed, and `job-ingestion-service` can remain scaffolded (structurally present, not functionally integrated) until Kafka integration begins
- Event consumers must be idempotent — enforced via the unique constraints each consumer upserts against (see Database Invariants below)
- Both services currently share one PostgreSQL database — this is an **initial deployment convenience, not shared ownership**. Every table has exactly one owning service: `job-ingestion-service` is the only writer to `raw_job_posting`; `job-platform-service` must never update or delete rows in it. Future cross-service integration goes through events/APIs, never direct cross-service table access, so the services can move to separate databases later without a redesign
- Transactional Outbox Pattern is the planned mechanism for reliably publishing Kafka events after a DB commit — documented, not built yet

Full detail: `docs/architecture.md`, `docs/domain-model.md`, `docs/ingestion-design.md`, `docs/deduplication-design.md`, `docs/matching-design.md`.

## Database Invariants

Full detail: `docs/database-design.md`. The schema below is **frozen** — do not redesign it unless implementation exposes a real, concrete problem.

- Only signals guaranteed unique in the source of truth are DB-unique (`(company_id, requisition_id)`, `job_source` external identity, `job_match`'s `(job_id, resume_id, algorithm_version)`). Fuzzy/candidate signals — `dedup_fingerprint`, `canonical_apply_url_hash`, `company.normalized_name`, `company.domain` — are indexed, never unique; merge decisions are application logic.
- Every `skill` has exactly one canonical self-alias in `skill_alias`; all skill-term resolution goes through `skill_alias.normalized_alias`, never `skill.normalized_name` directly.
- A user has at most one default resume (zero is valid for a new user); application logic ensures one exists before matching runs.
- Reapplication is allowed — there is no uniqueness constraint on `(user_id, job_id)` in `application`.
- `raw_job_posting` is immutable and owned solely by ingestion. `raw_job_processing` is the separate, mutable Job Processing record — one row per `(raw_job_posting_id, normalizer_version)`, updated in place on retry (`processing_status`, `attempt_count`, `error_message`, `processed_at`); no per-attempt history is modeled.
- `application` is archived, never hard-deleted (`archived_at`); its `application_status_history` is permanent and append-only. `saved_job` and `passed_job` may be hard-deleted freely.
- `application.job_match_score_at_application` is a frozen snapshot, independent of the live `job_match` row it references (`job_match_id`, nullable).
- Status fields are `TEXT` + `CHECK`, mapped to Java enums at the application layer — no native Postgres `ENUM` types.
- The physical table for the user entity is `app_user` (naming exception, avoids the reserved word); the Java entity is still named `User`.

## Coding Rules

- snake_case for every physical table and column name; Java entity/field names follow normal Java conventions and map explicitly where they diverge (e.g. `User` ↔ `app_user`)
- Never expose JPA entities directly through REST — use DTOs
- Keep business logic out of controllers
- Use constructor injection, not field injection
- Never write directly to a table owned by another service/module — go through that module's API or published event
- Immutable-by-design records (`raw_job_posting`, `resume` versions, `application_status_history` rows) are never updated in place after creation
- No destructive deletes on `application` / `application_status_history` — archive instead (`archived_at`)
- Status/enum-like DB columns are `TEXT` + `CHECK`, mapped to a Java enum — don't introduce native Postgres `ENUM` types
- Schema changes go through Flyway once it's introduced — no ad hoc DDL
- Don't introduce SQLite, GraphQL, Kafka, or OpenSearch — out of scope until explicitly approved
- Don't redesign the frozen database schema unless implementation surfaces a real, concrete problem — raise it, don't silently deviate

## Current Milestone

Repository scaffolding and documentation are complete. No Spring Boot code, no React code, no Docker Compose, no SQL migrations, no Kafka/OpenSearch yet.

Immediate next steps, in order:
1. Repository hygiene / Git
2. `job-platform-service` Spring Boot skeleton
3. PostgreSQL via Docker
4. Flyway configuration
5. V1 frozen-schema migration
