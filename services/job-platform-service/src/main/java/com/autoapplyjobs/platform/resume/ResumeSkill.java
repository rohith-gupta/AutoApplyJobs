package com.autoapplyjobs.platform.resume;

import com.autoapplyjobs.platform.job.Skill;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code resume_skill} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>A join row with its own independently generated {@code id} (not a
 * shared-primary-key one-to-one) linking one {@link Resume} to one
 * {@link Skill}. Both relationships are unidirectional
 * ({@code ResumeSkill -> Resume}, {@code ResumeSkill -> Skill}); neither
 * {@link Resume} nor {@link Skill} is modified to add a collection
 * back-reference.
 *
 * <p>{@code resume_id} cascades on delete ({@code ON DELETE CASCADE} - a
 * resume's extracted skills go with it) while {@code skill_id} restricts
 * ({@code ON DELETE RESTRICT} - a canonical skill can't be deleted while
 * any resume still references it), exactly mirroring the asymmetry already
 * present in the migration.
 *
 * <p><strong>Skill resolution note:</strong> this entity references the
 * already-canonical {@link Skill} row directly. It does not depend on
 * {@link com.autoapplyjobs.platform.job.SkillAlias} and implements no
 * alias-resolution logic - whatever process extracts skills from a resume
 * is expected to have already resolved raw text to a canonical
 * {@code Skill} (via {@code SkillAlias.normalizedAlias}, per
 * {@code CLAUDE.md}) before a {@code ResumeSkill} row is ever constructed.
 * That resolution step is a separate, later concern.
 *
 * <p><strong>Immutable</strong> parsed data, same posture as
 * {@link ResumeExperience} / {@link ResumeEducation}: no setters, every
 * field fixed at construction, {@code updatable = false} throughout. No
 * reprocessing/update behavior is introduced here.
 *
 * <p>{@code uq_resume_skill_resume_skill} (unique on {@code resume_id,
 * skill_id}) is declared below via {@link UniqueConstraint} for
 * documentation - it has no effect at runtime since Hibernate schema
 * generation is disabled ({@code ddl-auto=none}); the real enforcement is
 * PostgreSQL's constraint, exercised directly by this step's tests.
 */
@Entity
@Table(
        name = "resume_skill",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_resume_skill_resume_skill",
                columnNames = {"resume_id", "skill_id"}
        )
)
public class ResumeSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, updatable = false)
    private Resume resume;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false, updatable = false)
    private Skill skill;

    @Column(name = "proficiency", updatable = false)
    private String proficiency;

    @Column(name = "years_of_experience", precision = 4, scale = 1, updatable = false)
    private BigDecimal yearsOfExperience;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ResumeSkill() {
    }

    public ResumeSkill(Resume resume, Skill skill, String proficiency, BigDecimal yearsOfExperience) {
        this.resume = resume;
        this.skill = skill;
        this.proficiency = proficiency;
        this.yearsOfExperience = yearsOfExperience;
    }

    public UUID getId() {
        return id;
    }

    public Resume getResume() {
        return resume;
    }

    public Skill getSkill() {
        return skill;
    }

    public String getProficiency() {
        return proficiency;
    }

    public BigDecimal getYearsOfExperience() {
        return yearsOfExperience;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResumeSkill other)) {
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
        return "ResumeSkill{id=" + id + "}";
    }
}
