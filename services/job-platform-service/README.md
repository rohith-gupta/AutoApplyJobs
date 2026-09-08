# job-platform-service

Java / Spring Boot service. Not yet implemented.

Owns Job Processing (`company`, `job`, `job_source`, `job_location`,
`skill`, `skill_alias`, `job_skill`, `raw_job_processing`), Matching
(`job_match`, `job_match_skill`), User/Resume (`app_user`, `resume` and
related tables, `user_job_preference`), and Applications (`saved_job`,
`passed_job`, `application`, `application_status_history`). Exposes the
REST API consumed by `frontend/`.

See `../../docs/architecture.md`, `../../docs/database-design.md`,
`../../docs/deduplication-design.md`, `../../docs/matching-design.md`, and
the root `CLAUDE.md` before adding code here.
