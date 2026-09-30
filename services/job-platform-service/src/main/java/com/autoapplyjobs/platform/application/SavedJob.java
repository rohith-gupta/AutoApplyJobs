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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code saved_job} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}. No discrepancy between the
 * two was found for this table.
 *
 * <p>A join row with its own independently generated {@code id} (not a
 * shared-primary-key one-to-one) linking one {@link User} to one
 * {@link Job} - the user's current bookmark on that job ("interested,
 * revisit later"), per {@code docs/domain-model.md}. Both relationships
 * are unidirectional ({@code SavedJob -> User}, {@code SavedJob -> Job});
 * neither {@link User} nor {@link Job} is modified to add a collection
 * back-reference.
 *
 * <p>{@code user_id} and {@code job_id} both cascade on delete
 * ({@code ON DELETE CASCADE}) - a bookmark has no meaning once its user or
 * job is gone; unlike {@code job_skill}/{@code resume_skill}'s asymmetric
 * cascade/restrict split against {@code skill}, there is no canonical
 * taxonomy row here to protect from deletion.
 *
 * <p><strong>Identity - {@code (user_id, job_id)}:</strong> a
 * {@code SavedJob} represents one user's current bookmark of one canonical
 * {@link Job}; the semantic identity of that association is the pair
 * {@code (user_id, job_id)} itself, the same reasoning already applied to
 * {@link com.autoapplyjobs.platform.job.JobSkill} and
 * {@link com.autoapplyjobs.platform.matching.JobMatchSkill}. {@code id} is
 * {@code updatable = false} as always, and {@code user}/{@code job} are
 * likewise {@code updatable = false} - a row is never mutated from one
 * {@link User} or {@link Job} into another. Unsaving a job is expected to
 * delete the row outright (per {@code docs/domain-model.md}: "May be
 * removed outright") rather than repoint it - no delete/service logic is
 * implemented in this step.
 *
 * <p><strong>{@code saved_at} and {@code note} (mutability resolved after
 * the initial mapping step):</strong> V1 defines no {@code created_at}
 * column on this table at all - {@code saved_at} is the one and only
 * timestamp, supplied by the caller (not DB-generated via
 * {@code DEFAULT now()}), so it is mapped as a normal insertable column
 * rather than {@link Generated}.
 * <ul>
 *   <li>{@code savedAt} is {@code updatable = false}: it represents the
 *       timestamp of the user's save/bookmark event, a historical fact
 *       fixed once established - the same treatment as
 *       {@code Job.firstSeenAt}. It must not be rewritten merely because
 *       the bookmark's {@code note} is edited later.</li>
 *   <li>{@code note} remains JPA-updatable: it is current, user-editable
 *       bookmark metadata that may be changed at any time after the
 *       initial save, independent of when the bookmark itself was
 *       created.</li>
 * </ul>
 *
 * <p>{@code uq_saved_job_user_job} (unique on {@code user_id, job_id}) is
 * declared below via {@link UniqueConstraint} for documentation - it has
 * no effect at runtime since Hibernate schema generation is disabled
 * ({@code ddl-auto=none}); the real enforcement is PostgreSQL's
 * constraint, exercised directly by this step's tests.
 *
 * <p>No delete/unsave logic, {@code PassedJob}, {@code Application},
 * {@code ApplicationStatusHistory}, repositories, services, controllers,
 * or DTOs are added in this step.
 */
@Entity
@Table(
        name = "saved_job",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_saved_job_user_job",
                columnNames = {"user_id", "job_id"}
        )
)
public class SavedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Fixed once set - see the class Javadoc. One half of this row's own
     * identity pair ({@code user_id, job_id}). Not reassigned to a
     * different {@link User}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * Fixed once set - see the class Javadoc. The other half of this
     * row's identity pair. Not reassigned to a different {@link Job}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    /**
     * Caller-supplied, not DB-generated - see the class Javadoc. A
     * historical fact fixed once established - non-updatable. Not
     * rewritten when {@code note} is edited.
     */
    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;

    /** Current, user-editable bookmark metadata - see the class Javadoc. Mutable. */
    @Column(name = "note")
    private String note;

    /** Required by JPA. */
    protected SavedJob() {
    }

    public SavedJob(User user, Job job, Instant savedAt, String note) {
        this.user = user;
        this.job = job;
        this.savedAt = savedAt;
        this.note = note;
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

    public Instant getSavedAt() {
        return savedAt;
    }

    public String getNote() {
        return note;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SavedJob other)) {
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
        return "SavedJob{id=" + id + ", savedAt=" + savedAt + "}";
    }
}
