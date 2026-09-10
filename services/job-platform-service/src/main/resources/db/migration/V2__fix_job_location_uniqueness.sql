-- V2__fix_job_location_uniqueness.sql
--
-- Fixes a real database-semantic mismatch discovered in job_location's
-- uniqueness rule (see docs/database-design.md and the JobLocation
-- mapping/audit steps). V1__initial_schema.sql is frozen and not modified
-- - this is an additive correction, not a redesign of the logical rule.
--
-- Problem: uq_job_location_job_city_state_country_remote, as defined in
-- V1, is a standard PostgreSQL UNIQUE constraint. Standard SQL/PostgreSQL
-- unique-constraint semantics never treat two NULLs as equal, so any row
-- where city and/or state_region was NULL was effectively exempt from the
-- constraint - two rows meaning "remote, US, no city/state" (or "same
-- city, no state") could be inserted for the same job an unlimited number
-- of times, despite representing the same logical location. Confirmed
-- empirically in JobLocationEntityTest before this migration existed.
--
-- Fix: PostgreSQL 15+ supports UNIQUE ... NULLS NOT DISTINCT, which makes
-- NULL participate in the uniqueness comparison like any other value (two
-- NULLs in the same column are now treated as equal for this constraint).
-- We are on PostgreSQL 17, so this is available directly - no workaround
-- (e.g. COALESCE-based expression index) is needed.
--
-- The logical key is unchanged - still exactly
-- (job_id, city, state_region, country, is_remote). postal_code and
-- raw_location_text remain excluded from uniqueness, exactly as in V1.
-- The constraint name is unchanged too, so JobLocation.java's
-- documentation-only @UniqueConstraint annotation (same name, same
-- columns) still accurately names it.
--
-- No data is modified, deleted, or merged by this migration - there is no
-- DML here at all. If incompatible duplicate rows already existed, the
-- ADD CONSTRAINT below fails outright (migration failure) rather than
-- silently resolving them. On a fresh/empty job_location table (the only
-- state this schema has ever existed in, per this project's migration
-- history) this succeeds unconditionally.

ALTER TABLE job_location
    DROP CONSTRAINT uq_job_location_job_city_state_country_remote;

ALTER TABLE job_location
    ADD CONSTRAINT uq_job_location_job_city_state_country_remote
    UNIQUE NULLS NOT DISTINCT (job_id, city, state_region, country, is_remote);
