# job-ingestion-service

Java / Spring Boot service. Not yet implemented.

Owns `raw_job_posting` only — runs source connectors on a schedule and
writes raw payloads, unmodified. Never writes canonical data
(`company`, `job`, etc.) — that belongs to `job-platform-service`.

See `../../docs/ingestion-design.md` and the root `CLAUDE.md` before adding
code here.
