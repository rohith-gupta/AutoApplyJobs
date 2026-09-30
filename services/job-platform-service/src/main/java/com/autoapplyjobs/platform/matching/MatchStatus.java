package com.autoapplyjobs.platform.matching;

/**
 * Mirrors the {@code chk_job_match_skill_status} CHECK constraint on
 * {@code job_match_skill.match_status} in
 * {@code db/migration/V1__initial_schema.sql}: {@code match_status IN
 * ('MATCHED', 'PARTIAL', 'MISSING')}. Mapped via
 * {@code @Enumerated(EnumType.STRING)} - the database column stays plain
 * {@code TEXT} + {@code CHECK}, never a native Postgres {@code ENUM}
 * type, per {@code CLAUDE.md}.
 */
public enum MatchStatus {
    MATCHED,
    PARTIAL,
    MISSING
}
