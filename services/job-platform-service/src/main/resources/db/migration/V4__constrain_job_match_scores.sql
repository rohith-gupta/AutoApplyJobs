-- V4__constrain_job_match_scores.sql
--
-- Resolves a real schema gap discovered while mapping JobMatch's
-- PostgreSQL integration tests (see docs/database-design.md and
-- docs/matching-design.md). V1__initial_schema.sql is frozen and not
-- modified - this is an additive correction, not a redesign of the
-- logical rule.
--
-- Problem: job_match's score columns (overall_score and every
-- per-dimension sub-score) are plain NUMERIC(5,2) in V1, with no CHECK
-- constraint bounding their range - unlike status columns elsewhere in
-- this schema (chk_job_status, chk_raw_job_processing_status, etc.).
-- NUMERIC(5,2) only bounds precision/scale (values up to 999.99 in
-- magnitude); it does not enforce that a score is a valid percentage.
-- Confirmed empirically in JobMatchEntityTest before this migration
-- existed (a 999.99 overall_score was accepted without error).
--
-- Design clarification (this migration encodes it): every JobMatch score
-- - overall_score and all seven sub-scores (skills_score,
-- experience_score, title_score, responsibility_score, education_score,
-- location_score, preference_score) - represents a normalized percentage
-- in the range 0.00 through 100.00 inclusive. These are the category
-- scores themselves, not their already-weighted contribution to
-- overall_score; future matching weights (e.g. skills 40%, experience
-- 20%, ...) are applied by matching logic when computing overall_score
-- from the sub-scores - that weighting is not implemented here and has no
-- bearing on this constraint, which only bounds each column's own value.
--
-- Fix: add one CHECK constraint per score column, each named
-- chk_job_match_<column>_range, enforcing 0 <= score <= 100. NUMERIC(5,2)
-- is unchanged - still the type, still the precision/scale. Every
-- sub-score remains nullable; NULL continues to satisfy a CHECK
-- constraint under standard SQL three-valued logic (a NULL operand makes
-- the comparison UNKNOWN, and PostgreSQL only rejects a row when a CHECK
-- evaluates to FALSE, not UNKNOWN), so "this algorithm version doesn't
-- compute this dimension" remains fully expressible without a NULL-aware
-- workaround.
--
-- No data is modified, deleted, or merged by this migration - there is no
-- DML here at all. If any existing job_match row already violates the
-- 0-100 range, the ADD CONSTRAINT statements below fail outright
-- (migration failure) rather than silently clamping or discarding it. On
-- a fresh/empty job_match table (the only state this schema has ever
-- existed in, per this project's migration history) this succeeds
-- unconditionally.

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_overall_score_range CHECK (overall_score >= 0 AND overall_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_skills_score_range CHECK (skills_score >= 0 AND skills_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_experience_score_range CHECK (experience_score >= 0 AND experience_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_title_score_range CHECK (title_score >= 0 AND title_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_responsibility_score_range CHECK (responsibility_score >= 0 AND responsibility_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_education_score_range CHECK (education_score >= 0 AND education_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_location_score_range CHECK (location_score >= 0 AND location_score <= 100);

ALTER TABLE job_match
    ADD CONSTRAINT chk_job_match_preference_score_range CHECK (preference_score >= 0 AND preference_score <= 100);
