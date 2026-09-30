-- V6__constrain_application_frozen_score.sql
--
-- Resolves a real schema gap discovered while mapping Application (see
-- docs/database-design.md and docs/matching-design.md).
-- V1__initial_schema.sql through V5 are frozen and not modified - this is
-- an additive correction, not a redesign of the logical rule.
--
-- Problem: application.job_match_score_at_application is a plain
-- NUMERIC(5,2) in V1, with no CHECK constraint bounding its range -
-- unlike the live score it is frozen from. docs/matching-design.md
-- ("Snapshot at application time") documents this column as freezing
-- job_match.overall_score at the moment a user applies - the exact same
-- normalized 0.00-100.00 percentage that V4__constrain_job_match_scores.sql
-- already bounds on job_match itself. Being a frozen copy of that value
-- does not change what it represents; NUMERIC(5,2) alone only bounds
-- precision/scale (values up to 999.99 in magnitude), not that the score
-- is a valid percentage - the same class of gap V4 fixed for job_match's
-- own score columns.
--
-- Design clarification (this migration encodes it): application's
-- job_match_score_at_application, when present, represents the same
-- normalized percentage in the range 0.00 through 100.00 inclusive as
-- job_match.overall_score. It remains nullable and independent of
-- job_match_id (per docs/matching-design.md, it does not change even if
-- the live job_match row is later recomputed or its FK goes stale via
-- ON DELETE SET NULL) - this migration only bounds the frozen value's own
-- range, it does not add, remove, or reinterpret any relationship.
--
-- Fix: add a single CHECK constraint allowing NULL or any value in
-- [0, 100], the same NULL-aware shape used by
-- V5__constrain_job_match_skill_weight.sql (not V4's NOT NULL form,
-- since this column is nullable). NUMERIC(5,2) is unchanged - still the
-- type, still the precision/scale. The column remains nullable - not
-- made NOT NULL.
--
-- No data is modified, deleted, or merged by this migration - there is no
-- DML here at all. If any existing application row already has a
-- job_match_score_at_application outside [0, 100], the ADD CONSTRAINT
-- below fails outright (migration failure) rather than silently clamping
-- or discarding it. On a fresh/empty application table (the only state
-- this schema has ever existed in, per this project's migration history)
-- this succeeds unconditionally.

ALTER TABLE application
    ADD CONSTRAINT chk_application_frozen_score_range
    CHECK (job_match_score_at_application IS NULL OR
           (job_match_score_at_application >= 0 AND job_match_score_at_application <= 100));
