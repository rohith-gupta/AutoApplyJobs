package com.autoapplyjobs.platform.job;

/**
 * Mirrors the {@code chk_job_status} CHECK constraint on {@code job.status}
 * in {@code db/migration/V1__initial_schema.sql}: {@code status IN
 * ('ACTIVE', 'EXPIRED', 'CLOSED')}. Mapped via
 * {@code @Enumerated(EnumType.STRING)} - the database column stays plain
 * {@code TEXT} + {@code CHECK}, never a native Postgres {@code ENUM} type,
 * per {@code CLAUDE.md}.
 */
public enum JobStatus {
    ACTIVE,
    EXPIRED,
    CLOSED
}
