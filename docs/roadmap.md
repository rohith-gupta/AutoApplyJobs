# Roadmap

## Part One (this phase)

- Collect jobs from multiple permitted sources
- Normalize job data
- Deduplicate postings into unique canonical jobs
- Maintain a base/default resume per user
- Compute a job match percentage between a resume and each job
- Search and filter jobs
- Save / pass jobs
- Mark jobs as applied, recording the exact resume version used
- Track applications and their status history

## Explicitly excluded (Part Two — later, not now)

- Automatic job applications
- Automatic screening-question answers
- Recruiter messaging
- Interview automation
- Automatic follow-ups

## Also explicitly out of scope for now

Not excluded forever — just not part of the current design/build:

- SQLite anywhere (Postgres only, from local dev onward)
- GraphQL (REST only)
- Kafka itself (event *shapes* are designed in; the broker isn't wired up yet)
- OpenSearch
- Native Postgres `ENUM` types (TEXT + CHECK instead)

## Milestones

1. ✅ System architecture agreed and frozen
2. ✅ Domain model agreed
3. ✅ Database design agreed and frozen (snake_case, PostgreSQL)
4. ✅ Repository scaffolding and documentation *(this step)*
5. ⬜ SQL migrations for the frozen schema
6. ⬜ `job-platform-service` skeleton (Spring Boot, no business logic yet)
7. ⬜ `job-ingestion-service` skeleton (Spring Boot, no business logic yet)
8. ⬜ First real connector + `raw_job_posting` write path
9. ⬜ Normalization + deduplication implementation
10. ⬜ Resume upload + parsing implementation
11. ⬜ Matching implementation (v1 algorithm)
12. ⬜ REST API surface for search/filter, save/pass, applications
13. ⬜ Frontend skeleton (React)
14. ⬜ Docker Compose for local dev (Postgres + both services)
15. ⬜ Kafka introduced incrementally, replacing in-process event calls
16. ⬜ Transactional outbox implemented alongside Kafka introduction

## Current milestone

**Step 4 — repository scaffolding and documentation.** No Spring Boot code,
no React code, no Docker Compose, no SQL migrations, no Kafka/OpenSearch
yet.
