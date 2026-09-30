package com.autoapplyjobs.platform.application;

import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code passed_job} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}. No discrepancy between the
 * two was found for this table.
 *
 * <p>A user's dismissal of a {@link Job} ("not interested, don't show
 * again"), per {@code docs/domain-model.md}. Structurally the mirror image
 * of {@link SavedJob} - same columns in the same roles
 * ({@code passed_at}/{@code reason} vs. {@code saved_at}/{@code note}),
 * same {@code ON DELETE CASCADE} on both FKs, same
 * {@code (user_id, job_id)} uniqueness, same "may be removed outright"
 * lifecycle - so it follows {@link SavedJob}'s already-resolved mapping
 * decisions rather than re-deciding them:
 * <ul>
 *   <li>{@code user}/{@code job} are unidirectional, lazy, and
 *       {@code updatable = false} - the pair is this row's identity. A
 *       dismissal is never repointed to another user or job; un-passing a
 *       job deletes the row (no delete logic is implemented here).</li>
 *   <li>{@code passedAt} is caller-supplied (V1 defines no
 *       {@code DEFAULT} and no {@code created_at} on this table) and
 *       {@code updatable = false} - the timestamp of the dismissal event, a
 *       historical fact, the same treatment as
 *       {@code SavedJob.savedAt}.</li>
 *   <li>{@code reason} is JPA-updatable - free-text, user-supplied metadata
 *       on the dismissal, the same treatment as {@code SavedJob.note}.</li>
 * </ul>
 *
 * <p>{@code uq_passed_job_user_job} is declared via
 * {@link UniqueConstraint} for documentation only ({@code ddl-auto=none});
 * PostgreSQL enforces it.
 *
 * <p>No relationship to {@link SavedJob} is modeled: nothing in the schema
 * or docs prevents a job from being both saved and passed by the same
 * user, and any such exclusivity would be application logic.
 */
@Entity
@Table(
        name = "passed_job",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_passed_job_user_job",
                columnNames = {"user_id", "job_id"}
        )
)
public class PassedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Half of this row's identity pair - fixed once set. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The other half of this row's identity pair - fixed once set. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    /** Caller-supplied, not DB-generated. A historical fact - non-updatable. */
    @Column(name = "passed_at", nullable = false, updatable = false)
    private Instant passedAt;

    /** User-supplied dismissal metadata - mutable. */
    @Column(name = "reason")
    private String reason;

    /** Required by JPA. */
    protected PassedJob() {
    }

    public PassedJob(User user, Job job, Instant passedAt, String reason) {
        this.user = user;
        this.job = job;
        this.passedAt = passedAt;
        this.reason = reason;
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

    public Instant getPassedAt() {
        return passedAt;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PassedJob other)) {
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
        return "PassedJob{id=" + id + ", passedAt=" + passedAt + "}";
    }
}
