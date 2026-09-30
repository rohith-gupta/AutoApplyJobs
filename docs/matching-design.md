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

Every score — `overall_score` and all seven sub-scores — is a **normalized
percentage in the range 0.00 through 100.00 inclusive**, enforced by a
database `CHECK` constraint per column (`V4__constrain_job_match_scores.sql`;
see `docs/database-design.md`). A sub-score is the category's own
normalized percentage, computed independently for that dimension — it is
**not** its already-weighted contribution to `overall_score`. Weighting is
applied by matching logic when it combines the sub-scores into
`overall_score`, not baked into the sub-score values themselves. For
example, an eventual weighting scheme might be:

| Dimension | Weight |
|---|---|
| Skills | 40% |
| Experience | 20% |
| Title | 15% |
| Responsibilities | 10% |
| Education | 5% |
| Location | 5% |
| Preferences | 5% |

Under such a scheme, a `skills_score` of `80.00` means "this resume scored
80% on the skills dimension" — matching logic would then apply the 40%
weight when folding it into `overall_score`, not store `32.00` (`80 × 0.40`)
in `skills_score` itself. This weighting scheme is illustrative of the
*shape* of the calculation, not an implementation — no matching algorithm
is implemented as part of the schema/persistence layer.

`job_match_skill` gives the skill-level detail behind `skills_score`: for
each relevant skill, a `match_status` of `MATCHED`, `PARTIAL`, or `MISSING`
(not a plain boolean, so "resume mentions a related but not exact skill"
has somewhere to go), plus an optional `weight`.

### `job_match_skill.weight`

`weight` is a **relative skill-importance coefficient**, used by a
matching algorithm when aggregating individual skill-match results into
`skills_score`. It is a different kind of "weight" from the
dimension-level weighting table above (Skills 40%, Experience 20%, ...):
that table weights whole *dimensions* when composing `overall_score`;
`job_match_skill.weight` weights individual *skills* when composing the
`skills_score` dimension itself. Neither is implemented as matching logic
here - both are schema-level facts only.

`weight` is explicitly **not**:
- a match-quality percentage (`match_status` carries that)
- a `0.00`–`100.00` score like `job_match`'s own columns
- the already-weighted contribution to `overall_score`
- the same thing as `job_skill.is_required`

Rules:
- Nullable. `NULL` means no explicit per-skill weight was persisted; the
  algorithm version in question may apply its own default during
  calculation.
- When present, `weight` must be `>= 0` (database-enforced by
  `V5__constrain_job_match_skill_weight.sql`) - negative relative
  importance has no valid domain meaning.
- `0.00` is valid: a skill can contribute zero relative weight while
  still being representable for explanation/detail.
- Positive values are relative coefficients. Their magnitude only has
  meaning relative to other per-skill weights interpreted by the *same*
  algorithm version - weights for one `job_match` are not required to sum
  to `1`, are not required to sum to `100`, and `weight` is not a
  percentage, so there is no **domain-semantic** upper bound such as `1`
  or `100`. This is distinct from the column's ordinary representational
  limit: `weight` remains `NUMERIC(5,2)`, which still permits at most 3
  digits before the decimal point. A value such as `250.00` is therefore
  valid, while a value such as `1000.00` remains invalid - not because of
  `V5`'s `weight >= 0` semantic `CHECK` constraint, but because it exceeds
  what `NUMERIC(5,2)` can represent.
- How `MATCHED`/`PARTIAL`/`MISSING` convert to a numeric value, and how
  `job_skill.is_required` might influence a chosen `weight`, are future
  matching-algorithm decisions - not persisted or hard-coded as a direct
  mapping.

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
