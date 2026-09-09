-- V1__initial_schema.sql
--
-- Initial frozen schema for AutoApplyJobs, as specified in
-- docs/database-design.md. Do not redesign this schema here — if
-- implementation exposes a real problem, raise it and get the frozen
-- design amended first, then add a new migration.
--
-- Conventions (see docs/database-design.md / CLAUDE.md):
--   * UUID primary keys via gen_random_uuid() (built into PostgreSQL 13+,
--     no extension required)
--   * TIMESTAMPTZ for all timestamps
--   * NUMERIC for all money/percentage values
--   * snake_case physical names
--   * status/enum-like columns are TEXT + CHECK, never native ENUM
--   * "candidate signal" columns (dedup_fingerprint, canonical_apply_url_hash,
--     company.normalized_name, company.domain) are indexed but explicitly
--     NOT unique - merge/match decisions are application logic
--
-- app_user.email is CITEXT per the frozen design, which requires the
-- citext extension (not built into core Postgres, unlike gen_random_uuid()).
CREATE EXTENSION IF NOT EXISTS citext;


-- ============================================================================
-- User module: app_user, user_job_preference
-- ============================================================================

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         CITEXT NOT NULL,
    password_hash TEXT NOT NULL,
    full_name     TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE TABLE user_job_preference (
    user_id                     UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    preferred_locations         TEXT[],
    workplace_type_preference   TEXT[],
    employment_type_preference  TEXT[],
    salary_min_preference       NUMERIC(12,2),
    salary_currency             CHAR(3),
    sponsorship_required        BOOLEAN,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Deliberately denormalized (array columns) for simplicity - see
-- docs/database-design.md.


-- ============================================================================
-- Job Processing module: company, skill, skill_alias, job, job_source,
-- job_location, job_skill, raw_job_posting, raw_job_processing
-- ============================================================================

CREATE TABLE company (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    domain          TEXT,
    website_url     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No unique constraints: normalized_name and domain are candidate
    -- matching signals only, never enforced-unique identity.
);
CREATE INDEX idx_company_normalized_name ON company (normalized_name);
CREATE INDEX idx_company_domain ON company (domain);

CREATE TABLE skill (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_normalized_name UNIQUE (normalized_name)
);

CREATE TABLE skill_alias (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id         UUID NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    alias            TEXT NOT NULL,
    normalized_alias TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_alias_normalized_alias UNIQUE (normalized_alias)
);
CREATE INDEX idx_skill_alias_skill_id ON skill_alias (skill_id);
-- Invariant (application-enforced, not a DB constraint): every skill row
-- gets exactly one self-alias row here where normalized_alias equals
-- skill.normalized_name. All skill-term resolution goes through
-- skill_alias.normalized_alias.

CREATE TABLE job (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id               UUID NOT NULL REFERENCES company (id) ON DELETE RESTRICT,
    title                    TEXT NOT NULL,
    normalized_title         TEXT NOT NULL,
    description              TEXT NOT NULL,
    requisition_id           TEXT,
    canonical_apply_url      TEXT,
    canonical_apply_url_hash TEXT,
    employment_type          TEXT,
    remote_type              TEXT,
    salary_min               NUMERIC(12,2),
    salary_max               NUMERIC(12,2),
    salary_currency          CHAR(3),
    experience_min_years     NUMERIC(4,1),
    experience_max_years     NUMERIC(4,1),
    dedup_fingerprint        TEXT NOT NULL,
    posted_at                TIMESTAMPTZ,
    status                   TEXT NOT NULL DEFAULT 'ACTIVE',
    first_seen_at            TIMESTAMPTZ NOT NULL,
    last_seen_at             TIMESTAMPTZ NOT NULL,
    expires_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_job_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CLOSED'))
);
-- (company_id, requisition_id) is the one true DB-enforced identity signal
-- on job. dedup_fingerprint and canonical_apply_url_hash are candidate
-- signals only - indexed, deliberately NOT unique.
CREATE UNIQUE INDEX uq_job_company_requisition ON job (company_id, requisition_id) WHERE requisition_id IS NOT NULL;
CREATE INDEX idx_job_dedup_fingerprint ON job (dedup_fingerprint);
CREATE INDEX idx_job_canonical_apply_url_hash ON job (canonical_apply_url_hash);
CREATE INDEX idx_job_company_id ON job (company_id);
CREATE INDEX idx_job_status ON job (status);
CREATE INDEX idx_job_last_seen_at ON job (last_seen_at);
CREATE INDEX idx_job_posted_at ON job (posted_at);

CREATE TABLE job_source (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    source_name      TEXT NOT NULL,
    source_native_id TEXT,
    source_url       TEXT NOT NULL,
    first_seen_at    TIMESTAMPTZ NOT NULL,
    last_seen_at     TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- This pair is the external job identity.
CREATE UNIQUE INDEX uq_job_source_native_id ON job_source (source_name, source_native_id) WHERE source_native_id IS NOT NULL;
CREATE UNIQUE INDEX uq_job_source_url ON job_source (source_name, source_url) WHERE source_native_id IS NULL;
CREATE INDEX idx_job_source_job_id ON job_source (job_id);

CREATE TABLE job_location (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id            UUID NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    city              TEXT,
    state_region      TEXT,
    country           TEXT NOT NULL,
    postal_code       TEXT,
    is_remote         BOOLEAN NOT NULL DEFAULT false,
    raw_location_text TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_location_job_city_state_country_remote UNIQUE (job_id, city, state_region, country, is_remote)
);
CREATE INDEX idx_job_location_job_id ON job_location (job_id);

CREATE TABLE job_skill (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    skill_id    UUID NOT NULL REFERENCES skill (id) ON DELETE RESTRICT,
    is_required BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_skill_job_skill UNIQUE (job_id, skill_id)
);
CREATE INDEX idx_job_skill_job_id ON job_skill (job_id);
CREATE INDEX idx_job_skill_skill_id ON job_skill (skill_id);

CREATE TABLE raw_job_posting (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_name      TEXT NOT NULL,
    source_native_id TEXT,
    source_url       TEXT NOT NULL,
    raw_payload      JSONB NOT NULL,
    content_hash     TEXT NOT NULL,
    fetched_at       TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_raw_job_posting_source_content UNIQUE (source_name, source_url, content_hash)
    -- No FK out - ingestion owns this table exclusively; it is never
    -- modified after insert (immutable raw snapshot).
);
CREATE INDEX idx_raw_job_posting_content_hash ON raw_job_posting (content_hash);
CREATE INDEX idx_raw_job_posting_source_native_id ON raw_job_posting (source_name, source_native_id);

CREATE TABLE raw_job_processing (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_job_posting_id  UUID NOT NULL REFERENCES raw_job_posting (id) ON DELETE CASCADE,
    processing_status   TEXT NOT NULL DEFAULT 'PENDING',
    job_source_id       UUID REFERENCES job_source (id) ON DELETE SET NULL,
    normalizer_version  TEXT,
    processed_at        TIMESTAMPTZ,
    error_message       TEXT,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_raw_job_processing_status CHECK (processing_status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT uq_raw_job_processing_posting_normalizer UNIQUE (raw_job_posting_id, normalizer_version)
    -- One row per raw posting + normalizer version. Retries update this
    -- row in place (processing_status, attempt_count, error_message,
    -- processed_at) - no per-attempt history table.
);
CREATE INDEX idx_raw_job_processing_pending ON raw_job_processing (processing_status) WHERE processing_status = 'PENDING';
CREATE INDEX idx_raw_job_processing_posting_id ON raw_job_processing (raw_job_posting_id);
CREATE INDEX idx_raw_job_processing_job_source_id ON raw_job_processing (job_source_id);


-- ============================================================================
-- Resume module: resume, resume_profile, resume_skill, resume_experience,
-- resume_education
-- ============================================================================

CREATE TABLE resume (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    version_number    INTEGER NOT NULL,
    file_url          TEXT NOT NULL,
    original_filename TEXT,
    is_default        BOOLEAN NOT NULL DEFAULT false,
    uploaded_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_resume_user_version UNIQUE (user_id, version_number)
);
-- At most one default resume per user (zero is valid for a new user).
CREATE UNIQUE INDEX uq_resume_user_default ON resume (user_id) WHERE is_default = true;
CREATE INDEX idx_resume_user_id ON resume (user_id);

CREATE TABLE resume_profile (
    resume_id           UUID PRIMARY KEY REFERENCES resume (id) ON DELETE CASCADE,
    full_name           TEXT,
    headline            TEXT,
    years_of_experience NUMERIC(4,1),
    summary             TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE resume_skill (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id           UUID NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    skill_id            UUID NOT NULL REFERENCES skill (id) ON DELETE RESTRICT,
    proficiency         TEXT,
    years_of_experience NUMERIC(4,1),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_resume_skill_resume_skill UNIQUE (resume_id, skill_id)
);
CREATE INDEX idx_resume_skill_resume_id ON resume_skill (resume_id);
CREATE INDEX idx_resume_skill_skill_id ON resume_skill (skill_id);

CREATE TABLE resume_experience (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id    UUID NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    company_name TEXT NOT NULL,
    title        TEXT NOT NULL,
    start_date   DATE NOT NULL,
    end_date     DATE,
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    -- company_name is free text, intentionally not FK'd to company -
    -- resume experience is self-reported, not sourced from job postings.
);
CREATE INDEX idx_resume_experience_resume_id ON resume_experience (resume_id);

CREATE TABLE resume_education (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id        UUID NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    institution_name TEXT NOT NULL,
    degree           TEXT,
    field_of_study   TEXT,
    start_date       DATE,
    end_date         DATE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_resume_education_resume_id ON resume_education (resume_id);


-- ============================================================================
-- Matching module: job_match, job_match_skill
-- ============================================================================

CREATE TABLE job_match (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                UUID NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    resume_id             UUID NOT NULL REFERENCES resume (id) ON DELETE CASCADE,
    algorithm_version     TEXT NOT NULL,
    overall_score         NUMERIC(5,2) NOT NULL,
    skills_score          NUMERIC(5,2),
    experience_score      NUMERIC(5,2),
    title_score           NUMERIC(5,2),
    responsibility_score  NUMERIC(5,2),
    education_score       NUMERIC(5,2),
    location_score        NUMERIC(5,2),
    preference_score      NUMERIC(5,2),
    computed_at           TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Identity of a job_match: job + resume version + algorithm version.
    -- Recompute events upsert this row.
    CONSTRAINT uq_job_match_job_resume_algorithm UNIQUE (job_id, resume_id, algorithm_version)
);
CREATE INDEX idx_job_match_resume_id ON job_match (resume_id);
CREATE INDEX idx_job_match_job_id ON job_match (job_id);

CREATE TABLE job_match_skill (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_match_id UUID NOT NULL REFERENCES job_match (id) ON DELETE CASCADE,
    skill_id     UUID NOT NULL REFERENCES skill (id) ON DELETE RESTRICT,
    match_status TEXT NOT NULL,
    weight       NUMERIC(5,2),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_job_match_skill_status CHECK (match_status IN ('MATCHED', 'PARTIAL', 'MISSING')),
    CONSTRAINT uq_job_match_skill_match_skill UNIQUE (job_match_id, skill_id)
);
CREATE INDEX idx_job_match_skill_job_match_id ON job_match_skill (job_match_id);


-- ============================================================================
-- Applications module: saved_job, passed_job, application,
-- application_status_history
-- ============================================================================

CREATE TABLE saved_job (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id  UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id   UUID NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL,
    note     TEXT,
    CONSTRAINT uq_saved_job_user_job UNIQUE (user_id, job_id)
    -- May be hard-deleted (unlike application).
);
CREATE INDEX idx_saved_job_user_id ON saved_job (user_id);
CREATE INDEX idx_saved_job_job_id ON saved_job (job_id);

CREATE TABLE passed_job (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id   UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id    UUID NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    passed_at TIMESTAMPTZ NOT NULL,
    reason    TEXT,
    CONSTRAINT uq_passed_job_user_job UNIQUE (user_id, job_id)
    -- May be hard-deleted (unlike application).
);
CREATE INDEX idx_passed_job_user_id ON passed_job (user_id);
CREATE INDEX idx_passed_job_job_id ON passed_job (job_id);

CREATE TABLE application (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id                          UUID NOT NULL REFERENCES job (id) ON DELETE RESTRICT,
    resume_id                       UUID NOT NULL REFERENCES resume (id) ON DELETE RESTRICT,
    job_match_id                    UUID REFERENCES job_match (id) ON DELETE SET NULL,
    applied_via_job_source_id       UUID REFERENCES job_source (id) ON DELETE SET NULL,
    job_match_score_at_application  NUMERIC(5,2),
    applied_at                      TIMESTAMPTZ NOT NULL,
    current_status                  TEXT NOT NULL DEFAULT 'APPLIED',
    external_application_url        TEXT,
    archived_at                     TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_application_status CHECK (current_status IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN', 'UNKNOWN'))
    -- No unique constraint on (user_id, job_id): reapplication is allowed.
    -- Never hard-deleted - archived via archived_at instead.
);
CREATE INDEX idx_application_user_job ON application (user_id, job_id);
CREATE INDEX idx_application_resume_id ON application (resume_id);
CREATE INDEX idx_application_current_status ON application (current_status);
CREATE INDEX idx_application_user_applied_at_active ON application (user_id, applied_at DESC) WHERE archived_at IS NULL;
CREATE INDEX idx_application_user_status_applied_at_active ON application (user_id, current_status, applied_at DESC) WHERE archived_at IS NULL;

CREATE TABLE application_status_history (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    status         TEXT NOT NULL,
    changed_at     TIMESTAMPTZ NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_application_status_history_status CHECK (status IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN', 'UNKNOWN'))
    -- Append-only by design: no updated_at column, and rows are never
    -- modified after insert at the application layer (CLAUDE.md coding
    -- rules). Not enforced by a DB-level trigger/permission revoke in this
    -- migration - see docs/database-design.md for that as a future option.
);
CREATE INDEX idx_application_status_history_app_changed ON application_status_history (application_id, changed_at);

-- outbox_event is intentionally not created here - future table, not part
-- of this milestone (see docs/database-design.md).
