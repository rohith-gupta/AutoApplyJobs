# Database Design (Frozen)

PostgreSQL. All physical table and column names are snake_case. UUID
primary keys. `TIMESTAMPTZ` for all timestamps (UTC-safe). `NUMERIC` for
all money/salary fields (BigDecimal-compatible). Status/enum-like columns
are `TEXT` + `CHECK`, mapped to Java enums at the application layer — no
native Postgres `ENUM` types.

This schema is **frozen**. Do not redesign it unless implementation exposes
a real, concrete problem — raise it rather than deviating silently.

## Table list

| Table | Owner | Purpose |
|---|---|---|
| `app_user` | User | account holder (Java entity: `User`) |
| `company` | Job Processing | canonical employer, matched via signals |
| `raw_job_posting` | Ingestion | immutable source-payload snapshot |
| `raw_job_processing` | Job Processing | mutable processing record per raw posting + normalizer version |
| `job` | Job Processing | canonical, deduplicated job opportunity |
| `job_source` | Job Processing | links a job to each external source it was found on |
| `job_location` | Job Processing | one location for a job |
| `skill` | Job Processing | canonical skill taxonomy |
| `skill_alias` | Job Processing | alternate terms mapped to a canonical skill, incl. self-alias |
| `job_skill` | Job Processing | job ↔ skill |
| `resume` | Resume | one immutable resume version |
| `resume_profile` | Resume | parsed summary, 1:1 with resume |
| `resume_skill` | Resume | resume ↔ skill |
| `resume_experience` | Resume | one work-history entry |
| `resume_education` | Resume | one education entry |
| `user_job_preference` | User | 1:1 match-relevant preferences |
| `job_match` | Matching | scored job × resume × algorithm_version |
| `job_match_skill` | Matching | skill-level breakdown of a job_match |
| `saved_job` | Applications | user bookmark (hard-deletable) |
| `passed_job` | Applications | user dismissal (hard-deletable) |
| `application` | Applications | user's application, soft-archived |
| `application_status_history` | Applications | append-only status trail |
| `outbox_event` *(future)* | — | transactional outbox for Kafka publishing — documented, not built |

## Table detail

### app_user

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| email | CITEXT | NOT NULL | |
| password_hash | TEXT | NOT NULL | |
| full_name | TEXT | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

Unique: `email`.

### company

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| name | TEXT | NOT NULL | |
| normalized_name | TEXT | NOT NULL | candidate signal — indexed, not unique |
| domain | TEXT | NULL | candidate signal — indexed, not unique |
| website_url | TEXT | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

No unique constraints. Indexes: `normalized_name`, `domain`.

### raw_job_posting

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| source_name | TEXT | NOT NULL | |
| source_native_id | TEXT | NULL | |
| source_url | TEXT | NOT NULL | |
| raw_payload | JSONB | NOT NULL | untouched |
| content_hash | TEXT | NOT NULL | |
| fetched_at | TIMESTAMPTZ | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(source_name, source_url, content_hash)`. Indexes: `content_hash`,
`(source_name, source_native_id)`. No FK out — ingestion owns this table
exclusively and it is never modified after insert.

### raw_job_processing

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| raw_job_posting_id | UUID | NOT NULL | FK → raw_job_posting.id |
| processing_status | TEXT | NOT NULL | `PENDING` / `PROCESSING` / `PROCESSED` / `FAILED`; default `PENDING` |
| job_source_id | UUID | NULL | FK → job_source.id, `ON DELETE SET NULL` |
| normalizer_version | TEXT | NOT NULL | required at creation, as of `V3` |
| processed_at | TIMESTAMPTZ | NULL | |
| error_message | TEXT | NULL | |
| attempt_count | INTEGER | NOT NULL | default 0 |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(raw_job_posting_id, normalizer_version)` — one row per raw
posting + normalizer version. Retries **update this row in place**
(`processing_status`, `attempt_count`, `error_message`, `processed_at`); no
per-attempt history table. Indexes: `processing_status WHERE
processing_status = 'PENDING'`, `raw_job_posting_id`, `job_source_id`.

**History:** V1 defined `normalizer_version` as nullable, with the note
"required once `PROCESSED`" — implying a row could be created generically
and have its normalizer version filled in later. That was inconsistent
with `(raw_job_posting_id, normalizer_version)` being this table's
identity: an identity column can't be unknown at creation and set
afterward, and because standard SQL unique-constraint semantics never
treat two `NULL`s as equal, the uniqueness rule didn't even prevent
multiple `NULL`-normalizer_version rows for the same posting (the same
class of gap fixed for `job_location` in `V2`). Corrected in
`V3__require_raw_job_processing_normalizer_version.sql`: the normalizer
version must be known and supplied *when the processing record is
created* — a `RawJobProcessing` row is created only after the application
has already selected which normalizer will process a given raw posting,
never as a generic placeholder. Choosing a different normalizer version
means creating a different row, not mutating an existing one.
`V1__initial_schema.sql` itself was not modified.

### job

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| company_id | UUID | NOT NULL | FK → company.id, `ON DELETE RESTRICT` |
| title | TEXT | NOT NULL | |
| normalized_title | TEXT | NOT NULL | |
| description | TEXT | NOT NULL | |
| requisition_id | TEXT | NULL | |
| canonical_apply_url | TEXT | NULL | |
| canonical_apply_url_hash | TEXT | NULL | candidate signal — indexed, not unique |
| employment_type | TEXT | NULL | |
| remote_type | TEXT | NULL | |
| salary_min | NUMERIC(12,2) | NULL | |
| salary_max | NUMERIC(12,2) | NULL | |
| salary_currency | CHAR(3) | NULL | ISO 4217 |
| experience_min_years | NUMERIC(4,1) | NULL | |
| experience_max_years | NUMERIC(4,1) | NULL | |
| dedup_fingerprint | TEXT | NOT NULL | candidate signal — indexed, not unique |
| posted_at | TIMESTAMPTZ | NULL | as reported by source |
| status | TEXT | NOT NULL | `ACTIVE` / `EXPIRED` / `CLOSED`; default `ACTIVE` |
| first_seen_at | TIMESTAMPTZ | NOT NULL | |
| last_seen_at | TIMESTAMPTZ | NOT NULL | |
| expires_at | TIMESTAMPTZ | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(company_id, requisition_id)` — partial, `WHERE requisition_id IS
NOT NULL`. This is the one true DB-enforced identity signal on `job`;
everything else (`dedup_fingerprint`, `canonical_apply_url_hash`) is a
candidate signal only. Indexes: `dedup_fingerprint`,
`canonical_apply_url_hash`, `company_id`, `status`, `last_seen_at`,
`posted_at`.

### job_source

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE CASCADE` |
| source_name | TEXT | NOT NULL | |
| source_native_id | TEXT | NULL | |
| source_url | TEXT | NOT NULL | |
| first_seen_at | TIMESTAMPTZ | NOT NULL | |
| last_seen_at | TIMESTAMPTZ | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(source_name, source_native_id)` partial `WHERE source_native_id
IS NOT NULL`; `(source_name, source_url)` partial `WHERE source_native_id
IS NULL`. This pair is the external job identity. Index: `job_id`.

### job_location

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE CASCADE` |
| city | TEXT | NULL | |
| state_region | TEXT | NULL | |
| country | TEXT | NOT NULL | |
| postal_code | TEXT | NULL | |
| is_remote | BOOLEAN | NOT NULL | default false |
| raw_location_text | TEXT | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(job_id, city, state_region, country, is_remote)`, with `NULL`
values in `city`/`state_region` treated as **equal to each other** for
this purpose (`UNIQUE NULLS NOT DISTINCT`, PostgreSQL 15+). Index:
`job_id`.

**History:** V1 defined this as a standard `UNIQUE` constraint, under
which PostgreSQL's normal SQL-standard behavior (two `NULL`s are never
equal) meant a row with `city`/`state_region` unset was effectively exempt
- duplicate "remote, no city/state" rows for the same job could be
inserted without limit. Confirmed via integration tests, then corrected in
`V2__fix_job_location_uniqueness.sql` by switching to `NULLS NOT
DISTINCT`, so `NULL` now participates in the uniqueness check like any
other value. `V1__initial_schema.sql` itself was not modified.

### skill

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| name | TEXT | NOT NULL | |
| normalized_name | TEXT | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `normalized_name`.

### skill_alias

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| skill_id | UUID | NOT NULL | FK → skill.id, `ON DELETE CASCADE` |
| alias | TEXT | NOT NULL | |
| normalized_alias | TEXT | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `normalized_alias`. Index: `skill_id`.

**Invariant:** every `skill` row has exactly one corresponding
`skill_alias` row where `normalized_alias = skill.normalized_name` (a
self-alias). All skill-term resolution goes through
`skill_alias.normalized_alias`.

### job_skill

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE CASCADE` |
| skill_id | UUID | NOT NULL | FK → skill.id, `ON DELETE RESTRICT` |
| is_required | BOOLEAN | NOT NULL | default true |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(job_id, skill_id)`. Indexes: `job_id`, `skill_id`.

### resume

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| user_id | UUID | NOT NULL | FK → app_user.id, `ON DELETE CASCADE` |
| version_number | INTEGER | NOT NULL | |
| file_url | TEXT | NOT NULL | |
| original_filename | TEXT | NULL | |
| is_default | BOOLEAN | NOT NULL | default false |
| uploaded_at | TIMESTAMPTZ | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(user_id, version_number)`; `(user_id)` partial `WHERE is_default
= true` — **at most one** default resume per user (zero is valid for a new
user). Index: `user_id`.

**Immutability rule (clarified):** every column is immutable after
creation except `is_default`, which is the one sanctioned exception.
Switching a user's default resume updates this flag on existing rows
rather than creating a new resume version, and it never changes which
resume version a historical `application.resume_id` points at.

### resume_profile

| Column | Type | Null | Notes |
|---|---|---|---|
| resume_id | UUID | NOT NULL | PK and FK → resume.id, `ON DELETE CASCADE` (1:1) |
| full_name | TEXT | NULL | |
| headline | TEXT | NULL | |
| years_of_experience | NUMERIC(4,1) | NULL | |
| summary | TEXT | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

### resume_skill

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| resume_id | UUID | NOT NULL | FK → resume.id, `ON DELETE CASCADE` |
| skill_id | UUID | NOT NULL | FK → skill.id, `ON DELETE RESTRICT` |
| proficiency | TEXT | NULL | |
| years_of_experience | NUMERIC(4,1) | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(resume_id, skill_id)`. Indexes: `resume_id`, `skill_id`.

### resume_experience

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| resume_id | UUID | NOT NULL | FK → resume.id, `ON DELETE CASCADE` |
| company_name | TEXT | NOT NULL | free text, not FK'd to `company` |
| title | TEXT | NOT NULL | |
| start_date | DATE | NOT NULL | |
| end_date | DATE | NULL | null = current position |
| description | TEXT | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Index: `resume_id`.

### resume_education

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| resume_id | UUID | NOT NULL | FK → resume.id, `ON DELETE CASCADE` |
| institution_name | TEXT | NOT NULL | |
| degree | TEXT | NULL | |
| field_of_study | TEXT | NULL | |
| start_date | DATE | NULL | |
| end_date | DATE | NULL | null = ongoing |
| created_at | TIMESTAMPTZ | NOT NULL | |

Index: `resume_id`.

### user_job_preference

| Column | Type | Null | Notes |
|---|---|---|---|
| user_id | UUID | NOT NULL | PK and FK → app_user.id, `ON DELETE CASCADE` (1:1) |
| preferred_locations | TEXT[] | NULL | |
| workplace_type_preference | TEXT[] | NULL | |
| employment_type_preference | TEXT[] | NULL | |
| salary_min_preference | NUMERIC(12,2) | NULL | |
| salary_currency | CHAR(3) | NULL | |
| sponsorship_required | BOOLEAN | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

Deliberately denormalized (array columns) for simplicity.

### job_match

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE CASCADE` |
| resume_id | UUID | NOT NULL | FK → resume.id, `ON DELETE CASCADE` |
| algorithm_version | TEXT | NOT NULL | |
| overall_score | NUMERIC(5,2) | NOT NULL | normalized percentage, `0.00`–`100.00` inclusive, as of `V4` |
| skills_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| experience_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| title_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| responsibility_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| education_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| location_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| preference_score | NUMERIC(5,2) | NULL | normalized percentage, `0.00`–`100.00` inclusive when present, as of `V4` |
| computed_at | TIMESTAMPTZ | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(job_id, resume_id, algorithm_version)` — this is the identity of
a `job_match`; recompute events upsert this row. Indexes: `resume_id`,
`job_id`.

**Score semantics:** every score column — `overall_score` and all seven
per-dimension sub-scores — is a normalized percentage in the range `0.00`
through `100.00` inclusive, enforced by one `CHECK` constraint per column
(`chk_job_match_<column>_range`). A sub-score is the category's own
normalized percentage, **not** its already-weighted contribution to
`overall_score`: future matching weights (e.g. skills 40%, experience
20%, title 15%, responsibilities 10%, education 5%, location 5%,
preferences 5%) are applied by matching logic when computing
`overall_score` from the sub-scores, not baked into the sub-score values
themselves. NULL sub-scores remain valid regardless of the range
constraint — a NULL comparison is `UNKNOWN`, not `FALSE`, so
PostgreSQL's `CHECK` does not reject it; this is how "this algorithm
version doesn't compute this dimension" stays expressible.

**History:** V1 defined these columns as plain `NUMERIC(5,2)` with no
range constraint, which let clearly-invalid scores (e.g. outside 0–100)
be persisted without error. Confirmed empirically, then corrected in
`V4__constrain_job_match_scores.sql`. `V1__initial_schema.sql` itself was
not modified.

### job_match_skill

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| job_match_id | UUID | NOT NULL | FK → job_match.id, `ON DELETE CASCADE` |
| skill_id | UUID | NOT NULL | FK → skill.id, `ON DELETE RESTRICT` |
| match_status | TEXT | NOT NULL | `MATCHED` / `PARTIAL` / `MISSING` |
| weight | NUMERIC(5,2) | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Unique: `(job_match_id, skill_id)`. Index: `job_match_id`.

### saved_job

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| user_id | UUID | NOT NULL | FK → app_user.id, `ON DELETE CASCADE` |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE CASCADE` |
| saved_at | TIMESTAMPTZ | NOT NULL | |
| note | TEXT | NULL | |

Unique: `(user_id, job_id)`. Indexes: `user_id`, `job_id`. May be
hard-deleted.

### passed_job

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| user_id | UUID | NOT NULL | FK → app_user.id, `ON DELETE CASCADE` |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE CASCADE` |
| passed_at | TIMESTAMPTZ | NOT NULL | |
| reason | TEXT | NULL | |

Unique: `(user_id, job_id)`. Indexes: `user_id`, `job_id`. May be
hard-deleted.

### application

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| user_id | UUID | NOT NULL | FK → app_user.id, `ON DELETE CASCADE` |
| job_id | UUID | NOT NULL | FK → job.id, `ON DELETE RESTRICT` |
| resume_id | UUID | NOT NULL | FK → resume.id, `ON DELETE RESTRICT` — exact version used |
| job_match_id | UUID | NULL | FK → job_match.id, `ON DELETE SET NULL` |
| applied_via_job_source_id | UUID | NULL | FK → job_source.id, `ON DELETE SET NULL` |
| job_match_score_at_application | NUMERIC(5,2) | NULL | frozen snapshot, independent of `job_match_id` |
| applied_at | TIMESTAMPTZ | NOT NULL | |
| current_status | TEXT | NOT NULL | see statuses below; default `APPLIED` |
| external_application_url | TEXT | NULL | |
| archived_at | TIMESTAMPTZ | NULL | soft-archival marker |
| created_at | TIMESTAMPTZ | NOT NULL | |
| updated_at | TIMESTAMPTZ | NOT NULL | |

**No** unique constraint on `(user_id, job_id)` — reapplication is allowed.
Indexes: `(user_id, job_id)` (plain), `resume_id`, `current_status`,
`(user_id, applied_at DESC) WHERE archived_at IS NULL`, `(user_id,
current_status, applied_at DESC) WHERE archived_at IS NULL`.

Never hard-deleted — archived via `archived_at` instead.

### application_status_history

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| application_id | UUID | NOT NULL | FK → application.id, `ON DELETE CASCADE` |
| status | TEXT | NOT NULL | see statuses below |
| changed_at | TIMESTAMPTZ | NOT NULL | |
| note | TEXT | NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |

Append-only — no `updated_at`, rows are never modified after insert.
Index: `(application_id, changed_at)`.

### outbox_event *(future — not built for Phase One)*

| Column | Type | Null | Notes |
|---|---|---|---|
| id | UUID | NOT NULL | PK |
| aggregate_type | TEXT | NOT NULL | e.g. `Job`, `Resume`, `Application` |
| aggregate_id | UUID | NOT NULL | |
| event_type | TEXT | NOT NULL | e.g. `job.canonical.created` |
| payload | JSONB | NOT NULL | |
| created_at | TIMESTAMPTZ | NOT NULL | |
| published_at | TIMESTAMPTZ | NULL | |

Index (planned): `published_at WHERE published_at IS NULL`.

## Standardized status values

**`application.current_status` / `application_status_history.status`:**
`APPLIED`, `SCREENING`, `INTERVIEW`, `OFFER`, `REJECTED`, `WITHDRAWN`,
`UNKNOWN`.

**`raw_job_processing.processing_status`:** `PENDING`, `PROCESSING`,
`PROCESSED`, `FAILED`.

**`job.status`:** `ACTIVE`, `EXPIRED`, `CLOSED`.

**`job_match_skill.match_status`:** `MATCHED`, `PARTIAL`, `MISSING`.

All represented as `TEXT` + `CHECK` constraints; Java maps each to an enum.

## ER diagram

```
app_user(User) ──1───* resume ──1───1 resume_profile
  │            ├──1───* resume_skill >──*───1 skill ──1───* skill_alias
  │            ├──1───* resume_experience
  │            └──1───* resume_education
  ├──1───1 user_job_preference
  │
  ├──1───* saved_job      *───1── job
  ├──1───* passed_job     *───1── job
  └──1───* application    *───1── job          (reapplication allowed)
                │              *───1── resume  (version used)
                │              *───0..1── job_match
                │              *───0..1── job_source  (applied via)
                └──1───* application_status_history

company ──1───* job

job ──1───* job_source ──0..1───* raw_job_processing ──*───1── raw_job_posting
job ──1───* job_location
job ──1───* job_skill >──*───1 skill
job ──1───* job_match  *───1── resume
job_match ──1───* job_match_skill >──*───1 skill

(future) outbox_event — written in-transaction alongside any of the above; relayed to Kafka asynchronously
```
