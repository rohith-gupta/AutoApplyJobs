# Matching Design

Owned by Matching, inside `job-platform-service`. Computes how well a
resume fits a job.

## What a match is

A `job_match` row is identified by exactly three things:
**job + resume version + matching algorithm version**
(`UNIQUE(job_id, resume_id, algorithm_version)`).

- Scoped to a specific **resume version**, not just the user — a new
  resume upload can score differently against the same job.
- Scoped to a specific **algorithm version** — so improving the matching
  logic doesn't silently overwrite history; old and new scores can coexist,
  and which one is "current" is an application-layer decision (e.g. "use
  the highest `algorithm_version` per job/resume pair").

## Score shape

`job_match.overall_score` is required. Everything else is an optional
per-dimension sub-score, present only if the algorithm version in question
computes it:

- `skills_score`
- `experience_score`
- `title_score`
- `responsibility_score`
- `education_score`
- `location_score`
- `preference_score`

`job_match_skill` gives the skill-level detail behind `skills_score`: for
each relevant skill, a `match_status` of `MATCHED`, `PARTIAL`, or `MISSING`
(not a plain boolean, so "resume mentions a related but not exact skill"
has somewhere to go).

## Inputs

- `job` + `job_skill` (via `skill` / `skill_alias` for normalized terms)
- `resume_profile`, `resume_skill`, `resume_experience`, `resume_education`
- `user_job_preference` (feeds `preference_score` — location, workplace
  type, employment type, salary, sponsorship)

All skill comparisons resolve through `skill_alias.normalized_alias`
first, so "Postgres" on a resume matches "PostgreSQL" on a job posting.

## Triggers (event-driven)

| Event | Effect |
|---|---|
| `job.canonical.created` | compute `job_match` for that job against every user's default resume (or all resumes, depending on scale — an implementation decision, not a schema one) |
| `job.canonical.updated` | recompute affected `job_match` rows |
| `resume.processed` | compute `job_match` for that resume version against relevant jobs |
| `resume.default.changed` | recompute/prioritize matches for the newly-default resume |

Every consumer is idempotent by construction: recomputing writes to the
same `(job_id, resume_id, algorithm_version)` row via upsert, so replaying
an event twice produces the same result instead of duplicate rows.

## Snapshot at application time

When a user applies, `application.job_match_score_at_application` freezes
the score they saw at that moment. `application.job_match_id` optionally
points at the live `job_match` row for traceability, but the frozen
snapshot is the number that matters historically — even if that
`job_match` row is later recomputed under a new `algorithm_version`, or its
`job_id`/`resume_id` foreign keys go stale (`ON DELETE SET NULL`), the
snapshot on the application doesn't change.

## Algorithm evolution

Because `algorithm_version` is part of `job_match`'s identity, the schema
supports running a new algorithm version alongside the old one — comparing
results, gradually cutting over — without needing a migration or losing
history. What counts as "the current algorithm" for a given match request
is an application-layer concern, not enforced by the schema.
