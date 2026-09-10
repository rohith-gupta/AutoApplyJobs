package com.autoapplyjobs.platform.job;

/**
 * Mirrors the {@code chk_raw_job_processing_status} CHECK constraint on
 * {@code raw_job_processing.processing_status} in
 * {@code db/migration/V1__initial_schema.sql}: {@code processing_status IN
 * ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')}. Mapped via
 * {@code @Enumerated(EnumType.STRING)} - the database column stays plain
 * {@code TEXT} + {@code CHECK}, never a native Postgres {@code ENUM} type,
 * per {@code CLAUDE.md}.
 */
public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
