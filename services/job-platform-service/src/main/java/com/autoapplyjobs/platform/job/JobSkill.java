package com.autoapplyjobs.platform.job;

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
 * Maps to the {@code job_skill} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}. No discrepancy between the
 * two was found for this table.
 *
 * <p>A join row with its own independently generated {@code id} (not a
 * shared-primary-key one-to-one) linking one {@link Job} to one
 * {@link Skill}. Both relationships are unidirectional
 * ({@code JobSkill -> Job}, {@code JobSkill -> Skill}); neither {@link Job}
 * nor {@link Skill} is modified to add a collection back-reference.
 *
 * <p>{@code job_id} cascades on delete ({@code ON DELETE CASCADE} - a
 * job's extracted skill associations go with it) while {@code skill_id}
 * restricts ({@code ON DELETE RESTRICT} - a canonical skill can't be
 * deleted while any job still references it), exactly mirroring the
 * asymmetry already present in the migration and in
 * {@link com.autoapplyjobs.platform.resume.ResumeSkill}.
 *
 * <p><strong>Skill resolution note:</strong> this entity references the
 * already-canonical {@link Skill} row directly. It does not depend on
 * {@link SkillAlias} and implements no alias-resolution, extraction, or
 * matching logic - whatever process extracts skills from a job description
 * is expected to have already resolved raw text to a canonical
 * {@code Skill} (via {@code SkillAlias.normalizedAlias}, per
 * {@code CLAUDE.md}) before a {@code JobSkill} row is ever constructed.
 * That resolution step is a separate, later concern.
 *
 * <p><strong>Mutability (clarified after the initial mapping step):
 * {@code JobSkill} represents the association between one canonical
 * {@link Job} and one canonical {@link Skill}. The semantic identity of
 * that association is the pair {@code (job_id, skill_id)} itself - so
 * both halves of it are fixed once set, not independently
 * correctable:</strong>
 * <ul>
 *   <li>{@code job} is non-updatable. Nothing in the schema or design
 *       suggests a {@code JobSkill} row is ever reassigned to a
 *       <em>different</em> job.</li>
 *   <li>{@code skill} is also non-updatable. A prior version of this
 *       mapping treated it as correctable (by analogy to
 *       {@code Job.company}/{@code Job.requisitionId}), but that analogy
 *       doesn't hold: {@code company}/{@code requisitionId} are
 *       <em>attributes of</em> a job, while {@code skill} here is one half
 *       of the row's own identity pair. Mutating one {@code JobSkill} row
 *       from one {@link Skill} to another is not "correcting the job's
 *       skill data" - it silently destroys the record that the job was
 *       once associated with the original skill. Correcting the set of
 *       skills a job requires - during future reprocessing - is instead
 *       modeled at the association level: retain {@code JobSkill} rows
 *       that still match, delete rows for skills no longer identified,
 *       insert new rows for newly identified skills, and update
 *       {@code isRequired} on retained rows where needed. No such
 *       reconciliation logic is implemented in this step; this only makes
 *       the mapping itself correctly reflect that a single row's skill
 *       association is not mutated in place.</li>
 *   <li>{@code isRequired} is mutable extracted metadata (required vs.
 *       optional) with no role in the unique constraint - JPA-updatable,
 *       the same posture as {@code Job}'s other canonical content fields
 *       (e.g. {@code employmentType}). Reconciliation would update this on
 *       a retained association where the requirement-ness changed, without
 *       touching which skill the row identifies.</li>
 * </ul>
 *
 * <p>{@code uq_job_skill_job_skill} (unique on {@code job_id, skill_id})
 * is declared below via {@link UniqueConstraint} for documentation - it
 * has no effect at runtime since Hibernate schema generation is disabled
 * ({@code ddl-auto=none}); the real enforcement is PostgreSQL's
 * constraint. Since both {@code job} and {@code skill} are non-updatable,
 * this constraint is now only ever exercised on INSERT for this entity -
 * this step's tests confirm that plus the fact that mutating the
 * in-memory {@code skill} reference has no persisted effect at all.
 */
@Entity
@Table(
        name = "job_skill",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_job_skill_job_skill",
                columnNames = {"job_id", "skill_id"}
        )
)
public class JobSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Fixed once set - see the class Javadoc. Not reassigned to a
     * different {@link Job} by anything in the current schema/design.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    /**
     * Fixed once set - see the class Javadoc. One half of this row's own
     * identity pair ({@code job_id, skill_id}), not a correctable
     * attribute of the job. Not reassigned to a different {@link Skill};
     * correcting a job's skill set is modeled by reconciling which
     * {@code JobSkill} rows exist, not by mutating one row's skill.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false, updatable = false)
    private Skill skill;

    /** Mutable extracted metadata - see the class Javadoc. */
    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected JobSkill() {
    }

    public JobSkill(Job job, Skill skill, boolean isRequired) {
        this.job = job;
        this.skill = skill;
        this.isRequired = isRequired;
    }

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public Skill getSkill() {
        return skill;
    }

    public boolean isRequired() {
        return isRequired;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobSkill other)) {
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
        return "JobSkill{id=" + id + ", isRequired=" + isRequired + "}";
    }
}
