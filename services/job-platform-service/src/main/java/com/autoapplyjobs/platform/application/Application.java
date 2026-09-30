package com.autoapplyjobs.platform.application;

import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.job.JobSource;
import com.autoapplyjobs.platform.matching.JobMatch;
import com.autoapplyjobs.platform.resume.Resume;
import com.autoapplyjobs.platform.user.User;
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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code application} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} as amended by
 * {@code V6__constrain_application_frozen_score.sql} (the source of
 * truth) and described in {@code docs/database-design.md}. No
 * discrepancy between the two was found for this table.
 *
 * <p>Records that a {@link User} applied to a {@link Job} using one
 * specific {@link Resume} version, with the match score at that moment
 * frozen in place ({@code docs/domain-model.md}). All relationships are
 * unidirectional and lazy; no collection back-reference is added to
 * {@link User}, {@link Job}, {@link Resume}, {@link JobMatch},
 * {@link JobSource}, or to this entity for
 * {@link ApplicationStatusHistory}.
 *
 * <p><strong>No uniqueness on {@code (user_id, job_id)}:</strong>
 * reapplication is allowed (V1, {@code CLAUDE.md}), so no
 * {@code @UniqueConstraint} is declared.
 *
 * <p><strong>ON DELETE behavior (V1):</strong> {@code user_id} cascades;
 * {@code job_id} and {@code resume_id} restrict (an application must keep
 * pointing at the job and exact resume version used);
 * {@code job_match_id} and {@code applied_via_job_source_id} are set to
 * {@code NULL} when the referenced row is deleted.
 *
 * <p><strong>Mutability:</strong>
 * <ul>
 *   <li>{@code user}, {@code job}, {@code resume} - {@code updatable =
 *       false}. {@code CLAUDE.md}: historical application rows keep
 *       referencing the exact resume version used at application time,
 *       regardless of later default-resume changes.</li>
 *   <li>{@code jobMatchScoreAtApplication} - {@code updatable = false}.
 *       Documented as a frozen snapshot, independent of the live
 *       {@code job_match} row it came from
 *       ({@code docs/matching-design.md}). As of
 *       {@code V6__constrain_application_frozen_score.sql}, PostgreSQL
 *       additionally enforces {@code NULL} or {@code 0.00}-{@code 100.00}
 *       inclusive when present - the same normalized-percentage range
 *       {@code V4} already enforces on the live
 *       {@code job_match.overall_score} this column is frozen from; being
 *       a frozen copy doesn't change what the value represents.</li>
 *   <li>{@code jobMatch}, {@code appliedViaJobSource} - {@code updatable =
 *       false}. Not specified by the docs; confirmed with the project
 *       owner during this mapping step: both record application-time facts
 *       (the match the frozen score was taken from; the source the user
 *       applied through). PostgreSQL may still set either to {@code NULL}
 *       via {@code ON DELETE SET NULL} - that is a DB-side action outside
 *       JPA's {@code updatable} flag.</li>
 *   <li>{@code appliedAt} - {@code updatable = false}. Caller-supplied
 *       historical fact, the same treatment as
 *       {@code SavedJob.savedAt}.</li>
 *   <li>{@code currentStatus} - mutable. The current position in the
 *       status lifecycle; the trail of changes lives in
 *       {@link ApplicationStatusHistory}. Keeping the two consistent, and
 *       any transition rules, are application logic - not implemented
 *       here.</li>
 *   <li>{@code externalApplicationUrl} - mutable. Not specified by the
 *       docs; confirmed with the project owner during this mapping step as
 *       user-editable metadata, the same posture as
 *       {@code SavedJob.note}.</li>
 *   <li>{@code archivedAt} - mutable. The soft-archive marker;
 *       applications are archived, never hard-deleted. No archive/delete
 *       logic is implemented here, and nothing at the DB level prevents a
 *       hard {@code DELETE} - that rule is application-layer.</li>
 * </ul>
 *
 * <p>{@code currentStatus} is always supplied by the caller rather than
 * relying on V1's {@code DEFAULT 'APPLIED'} - the same approach as
 * {@code RawJobProcessing.processingStatus}. {@code updatedAt} is mapped as
 * DB-generated-at-insert-only, the same unresolved timestamp-maintenance
 * gap already flagged on {@code Job.updatedAt}.
 */
@Entity
@Table(name = "application")
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    /** The exact resume version used - see the class Javadoc. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, updatable = false)
    private Resume resume;

    /** Optional traceability link; fixed at creation, DB may null it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_match_id", updatable = false)
    private JobMatch jobMatch;

    /** Optional; fixed at creation, DB may null it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_via_job_source_id", updatable = false)
    private JobSource appliedViaJobSource;

    /** Frozen snapshot - see the class Javadoc. */
    @Column(name = "job_match_score_at_application", precision = 5, scale = 2, updatable = false)
    private BigDecimal jobMatchScoreAtApplication;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false)
    private ApplicationStatus currentStatus;

    @Column(name = "external_application_url")
    private String externalApplicationUrl;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** DB-generated-at-insert-only - see the class Javadoc. */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Application() {
    }

    public Application(User user, Job job, Resume resume, JobMatch jobMatch, JobSource appliedViaJobSource,
                       BigDecimal jobMatchScoreAtApplication, Instant appliedAt,
                       ApplicationStatus currentStatus, String externalApplicationUrl) {
        this.user = user;
        this.job = job;
        this.resume = resume;
        this.jobMatch = jobMatch;
        this.appliedViaJobSource = appliedViaJobSource;
        this.jobMatchScoreAtApplication = jobMatchScoreAtApplication;
        this.appliedAt = appliedAt;
        this.currentStatus = currentStatus;
        this.externalApplicationUrl = externalApplicationUrl;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Job getJob() {
        return job;
    }

    public Resume getResume() {
        return resume;
    }

    public JobMatch getJobMatch() {
        return jobMatch;
    }

    public JobSource getAppliedViaJobSource() {
        return appliedViaJobSource;
    }

    public BigDecimal getJobMatchScoreAtApplication() {
        return jobMatchScoreAtApplication;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public ApplicationStatus getCurrentStatus() {
        return currentStatus;
    }

    public String getExternalApplicationUrl() {
        return externalApplicationUrl;
    }

    public Instant getArchivedAt() {
        return archivedAt;
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
        if (!(o instanceof Application other)) {
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
        return "Application{id=" + id + ", currentStatus=" + currentStatus + ", appliedAt=" + appliedAt + "}";
    }
}
