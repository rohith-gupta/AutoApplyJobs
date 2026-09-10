package com.autoapplyjobs.platform.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code raw_job_processing} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} as amended by
 * {@code V3__require_raw_job_processing_normalizer_version.sql} (the
 * source of truth) and described in {@code docs/database-design.md}. No
 * discrepancy between the two was found for this table.
 *
 * <p><strong>Service ownership boundary:</strong> {@code raw_job_posting}
 * is owned exclusively by {@code job-ingestion-service}
 * ({@code CLAUDE.md}, {@code docs/architecture.md},
 * {@code docs/ingestion-design.md}). {@code job-platform-service} must
 * never create, own, update, or delete rows there, so no
 * {@code RawJobPosting} JPA entity exists in this codebase and
 * {@code raw_job_posting_id} is deliberately mapped as a plain
 * {@link UUID} scalar, <strong>not</strong> a {@code @ManyToOne}
 * relationship - unlike every other FK in this codebase, which is mapped
 * as a real relationship to a same-service entity. The
 * {@code REFERENCES raw_job_posting (id) ON DELETE CASCADE} constraint
 * itself remains entirely PostgreSQL's responsibility; nothing here
 * enforces or assumes it beyond storing the id.
 *
 * <p>{@code job_source_id}, by contrast, references {@code job_source}
 * (owned by this same service, and already mapped as
 * {@link JobSource}), so it <em>is</em> mapped as a real
 * {@code @ManyToOne} - the usual pattern used throughout this codebase.
 * The relationship is unidirectional ({@code RawJobProcessing ->
 * JobSource} only); {@link JobSource} is not modified to add a collection
 * back-reference.
 *
 * <p><strong>Identity and mutability:</strong> a row represents the
 * current processing state for one {@code (raw_job_posting_id,
 * normalizer_version)} pair. Per {@code docs/database-design.md} /
 * {@code CLAUDE.md}, that pair is immutable after creation, and retries
 * update the same row in place rather than creating a new one - no
 * per-attempt history table exists. <strong>The normalizer version must
 * therefore be known before the processing record is created</strong> - a
 * {@code RawJobProcessing} row is created only after the application has
 * already selected which normalizer will process a given raw posting,
 * never as a generic placeholder with {@code normalizer_version = NULL}.
 * Choosing a different normalizer version means creating a different
 * {@code RawJobProcessing} row, not mutating an existing one's normalizer
 * version. This is enforced by
 * {@code V3__require_raw_job_processing_normalizer_version.sql}, which
 * makes {@code normalizer_version NOT NULL} - superseding V1's original
 * "required once {@code PROCESSED}" framing (nullable, filled in later),
 * which was inconsistent with treating the pair as this table's identity;
 * see that migration for the full reasoning.
 * <ul>
 *   <li>{@code id}, {@code rawJobPostingId}, {@code normalizerVersion},
 *       and {@code createdAt} are non-updatable - the identity pair plus
 *       the standard non-updatable PK/creation-timestamp treatment used
 *       everywhere in this codebase.</li>
 *   <li>{@code processingStatus}, {@code jobSource}, {@code processedAt},
 *       {@code errorMessage}, and {@code attemptCount} are the mutable
 *       retry/result state - all JPA-updatable, updated in place as a
 *       retry progresses (e.g. {@code PENDING -> PROCESSING -> PROCESSED}
 *       or {@code FAILED}, {@code attemptCount} incrementing,
 *       {@code errorMessage} populated then cleared on a later successful
 *       attempt, {@code jobSource} set once normalization resolves this
 *       posting to a {@link JobSource} row).</li>
 * </ul>
 * No setters and no processing-workflow/retry service logic are added in
 * this step; this only makes the JPA metadata correct for when such logic
 * exists.
 *
 * <p>{@code processingStatus} maps to {@link ProcessingStatus} via
 * {@code @Enumerated(EnumType.STRING)}, mirroring the migration's
 * {@code chk_raw_job_processing_status} CHECK constraint - never a native
 * Postgres {@code ENUM} type.
 *
 * <p>{@code updated_at} is mapped the same DB-generated-at-insert-only way
 * as everywhere else in this codebase ({@code DEFAULT now()}, no update
 * trigger exists) - the same known, unresolved timestamp-maintenance gap
 * already flagged on {@link Job} and {@link JobSource}, not solved here;
 * this table's V1 definition does not require anything different.
 *
 * <p>{@code uq_raw_job_processing_posting_normalizer} (unique on
 * {@code raw_job_posting_id, normalizer_version}) is declared below via
 * {@link UniqueConstraint} for documentation - it has no effect at
 * runtime since Hibernate schema generation is disabled
 * ({@code ddl-auto=none}); the real enforcement is PostgreSQL's
 * constraint, exercised directly by this step's tests.
 */
@Entity
@Table(
        name = "raw_job_processing",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_raw_job_processing_posting_normalizer",
                columnNames = {"raw_job_posting_id", "normalizer_version"}
        )
)
public class RawJobProcessing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * A plain UUID scalar, not a {@code @ManyToOne} - see the class
     * Javadoc's service-ownership-boundary note. Half of this row's
     * identity pair; fixed once set.
     */
    @Column(name = "raw_job_posting_id", nullable = false, updatable = false)
    private UUID rawJobPostingId;

    /** Mutable retry/result state - see the class Javadoc. */
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private ProcessingStatus processingStatus;

    /**
     * Set once normalization resolves this raw posting to a
     * {@link JobSource} row - see the class Javadoc. JPA-updatable.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_source_id")
    private JobSource jobSource;

    /**
     * The other half of this row's identity pair - see the class Javadoc.
     * Fixed once set, and required at creation time (never NULL) as of
     * {@code V3__require_raw_job_processing_normalizer_version.sql}.
     */
    @Column(name = "normalizer_version", nullable = false, updatable = false)
    private String normalizerVersion;

    /** Mutable retry/result state - see the class Javadoc. */
    @Column(name = "processed_at")
    private Instant processedAt;

    /** Mutable retry/result state - see the class Javadoc. */
    @Column(name = "error_message")
    private String errorMessage;

    /** Mutable retry/result state - see the class Javadoc. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Mapped as DB-generated-at-insert-only, the same unresolved
     * timestamp-maintenance gap already flagged on {@link Job#updatedAt} -
     * not solved in this step.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected RawJobProcessing() {
    }

    public RawJobProcessing(UUID rawJobPostingId, ProcessingStatus processingStatus, JobSource jobSource,
                             String normalizerVersion, Instant processedAt, String errorMessage,
                             int attemptCount) {
        this.rawJobPostingId = rawJobPostingId;
        this.processingStatus = processingStatus;
        this.jobSource = jobSource;
        this.normalizerVersion = normalizerVersion;
        this.processedAt = processedAt;
        this.errorMessage = errorMessage;
        this.attemptCount = attemptCount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRawJobPostingId() {
        return rawJobPostingId;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public JobSource getJobSource() {
        return jobSource;
    }

    public String getNormalizerVersion() {
        return normalizerVersion;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RawJobProcessing other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "RawJobProcessing{id=" + id + ", processingStatus=" + processingStatus + "}";
    }
}
