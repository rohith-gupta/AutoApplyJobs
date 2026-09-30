package com.autoapplyjobs.platform.application;

/**
 * Mirrors both the {@code chk_application_status} CHECK constraint on
 * {@code application.current_status} and the
 * {@code chk_application_status_history_status} CHECK constraint on
 * {@code application_status_history.status} in
 * {@code db/migration/V1__initial_schema.sql} - the two constraints allow
 * the identical set: {@code ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER',
 * 'REJECTED', 'WITHDRAWN', 'UNKNOWN')}, matching the standardized status
 * list in {@code docs/database-design.md}. Mapped via
 * {@code @Enumerated(EnumType.STRING)} - the database columns stay plain
 * {@code TEXT} + {@code CHECK}, never a native Postgres {@code ENUM} type,
 * per {@code CLAUDE.md}.
 *
 * <p>No transition rules are encoded here - which status may follow which
 * is not documented and is not a persistence concern.
 */
public enum ApplicationStatus {
    APPLIED,
    SCREENING,
    INTERVIEW,
    OFFER,
    REJECTED,
    WITHDRAWN,
    UNKNOWN
}
