# AutoApplyJobs

A job aggregation application. It collects job postings from multiple
permitted sources, deduplicates them into unique job opportunities, scores
each one against your resume, and lets you save, pass, and track
applications through to an outcome.

## Status

Early scaffolding stage. The system architecture and the database design are
**frozen** for Part One. No implementation exists yet — no backend code, no
frontend code, no infrastructure code, no database migrations.

## Part One Scope

- Collect jobs from multiple permitted sources, normalize, and deduplicate them
- Maintain a base/default resume per user and compute a job match percentage
- Search and filter jobs
- Save / pass jobs, mark jobs as applied (recording the exact resume version used)
- Track applications and their status history

**Not in this phase:** automatic job applications, automatic screening
answers, recruiter messaging, interview automation, automatic follow-ups.
See `docs/roadmap.md` for the full breakdown.

## Repository Layout

```
/
├── CLAUDE.md                  guidance for AI assistants / contributors
├── README.md                  this file
├── docs/                      architecture, domain, database, and process design
├── frontend/                  React app (not yet implemented)
├── services/
│   ├── job-platform-service/  core domain, matching, applications, REST API (not yet implemented)
│   └── job-ingestion-service/ source connectors, raw job ingestion (not yet implemented)
└── infrastructure/
    └── docker/                local/deployment infrastructure (not yet implemented)
```

## Documentation

| Doc | Covers |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | System components, data flow, service boundaries, event design |
| [`docs/domain-model.md`](docs/domain-model.md) | Domain entities and how they relate |
| [`docs/database-design.md`](docs/database-design.md) | Frozen PostgreSQL schema — tables, constraints, indexes |
| [`docs/ingestion-design.md`](docs/ingestion-design.md) | How job postings are collected and stored raw |
| [`docs/deduplication-design.md`](docs/deduplication-design.md) | How duplicate postings collapse into one canonical job |
| [`docs/matching-design.md`](docs/matching-design.md) | How a resume is scored against a job |
| [`docs/roadmap.md`](docs/roadmap.md) | Part One / Part Two scope and milestones |

## Technology Stack

Java/Spring Boot backend (two services), React frontend, PostgreSQL, REST
APIs, with Kafka-shaped event boundaries designed in from the start and
introduced incrementally. See `CLAUDE.md` for the full list of decisions.
