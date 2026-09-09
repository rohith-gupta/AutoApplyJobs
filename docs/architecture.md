# Architecture

## Overview

The system is a modular monolith for now, split into two deployable
services along data-ownership lines, backed by a single PostgreSQL
database. Async processing is designed around named events from day one,
even though Kafka itself is introduced incrementally — locally, an event is
just a function call or an outbox-table row; the contracts are what matter.

## Data flow

```
External Source
    │ (pull)
    ▼
Ingestion Service ──writes──▶ raw_job_posting
    │ publishes: raw_job_posting.ingested
    ▼
Job Processing (job-platform-service)
    │ normalize → deduplicate → upsert
    ├──▶ company
    ├──▶ job
    ├──▶ job_source   (links job to the source; raw_job_processing tracks the attempt)
    ├──▶ job_location
    └──▶ job_skill
    │ publishes: job.canonical.created / job.canonical.updated
    ▼
Matching (job-platform-service)  (consumes job.canonical.*, resume.processed, resume.default.changed)
    ├──▶ job_match
    └──▶ job_match_skill
    ▼
PostgreSQL
    ▲
    │ reads across owned tables
API Server (REST only)
    ▲
    │ HTTP
Frontend

User-driven, synchronous paths (straight through the API, no event needed):
  saved_job / passed_job
  application, application_status_history  ◀── Applications module ◀── API

Resume side:
User uploads/edits resume ──▶ Resume module
    ├──▶ resume
    ├──▶ resume_profile
    ├──▶ resume_skill
    └──▶ resume_education
    │ publishes: resume.processed   (parsing finished)
    │ publishes: resume.default.changed   (is_default flips)
    ▼
Matching recomputes job_match for affected jobs
```

## Components

| Component | Responsibility |
|---|---|
| Ingestion connectors | one per source; pull raw postings |
| Ingestion Service | schedules connectors, writes `raw_job_posting` (immutable), publishes ingestion events |
| Job Processing | normalizes, deduplicates, owns `company`, `job`, `job_source`, `job_location`, `skill`, `skill_alias`, `job_skill`, `raw_job_processing` |
| Matching | scores resume × job, owns `job_match`, `job_match_skill` |
| User | owns `app_user`, `user_job_preference` |
| Resume | owns `resume`, `resume_profile`, `resume_skill`, `resume_experience`, `resume_education` |
| Applications | owns `saved_job`, `passed_job`, `application`, `application_status_history` |
| API Server | REST only; the sole thing the frontend talks to |
| Frontend | browsing, resume management, application tracking UI |

## Service boundaries

Two deployable services, split along the ownership table above:

- **`job-ingestion-service`** — connectors, scheduler, `raw_job_posting` only. Never writes canonical data.
- **`job-platform-service`** — Job Processing, Matching, User, Resume, Applications, and the REST API surface consumed by the frontend.

Neither service reaches into a table it doesn't own directly — it goes
through the owning module's API or a published event. This boundary is what
lets a service split further later without a redesign.

## Event-oriented design

| Event | Published by | Consumed by |
|---|---|---|
| `raw_job_posting.ingested` | Ingestion | Job Processing |
| `job.canonical.created` / `job.canonical.updated` | Job Processing | Matching |
| `resume.processed` | User/Resume | Matching |
| `resume.default.changed` | User/Resume | Matching |
| `application.status_changed` | Applications | *(reserved — no consumer yet)* |

Every consumer above must be idempotent — replaying the same event twice
must not create duplicate data. This is enforced through unique constraints
each consumer upserts against (see `docs/database-design.md`), not through
distributed dedup logic.

**Transactional Outbox Pattern (future):** once Kafka is wired in, the same
DB transaction that writes a business row also writes an `outbox_event`
row; a relay process (polling, or CDC via Debezium) publishes it to Kafka
and marks it published. This avoids the dual-write problem of writing to
Postgres and Kafka as two separate, uncoordinated operations. Not built for
Phase One.

## Local development vs. cloud

Same component boundaries in both; only the infrastructure behind each one
changes.

| Component | Local dev | Cloud (later) |
|---|---|---|
| Scheduler | interval loop | managed cron |
| Event bus | in-process calls / outbox table polling | Kafka |
| Ingestion / Job Processing / Matching | functions within one process per service | independently scalable deployments |
| Database | Postgres (Docker or local install) | managed Postgres |
| API Server | single process | containerized, behind a load balancer |
| Frontend | dev server | static hosting/CDN |

## API style

REST only. No GraphQL, for this phase or until explicitly revisited.

## What's deliberately out of this phase

SQLite (Postgres only, always), GraphQL, Kafka itself (event *shapes* exist
now, the broker doesn't yet), OpenSearch, Spring Boot code, React code,
Docker Compose, SQL migrations. See `docs/roadmap.md`.
