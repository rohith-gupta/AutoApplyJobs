package com.autoapplyjobs.platform.matching;

import com.autoapplyjobs.platform.job.Skill;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code job_match_skill} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} as amended by
 * {@code V5__constrain_job_match_skill_weight.sql} (the source of truth,
 * untouched by {@code V2}/{@code V3}/{@code V4}) and described in
 * {@code docs/database-design.md} / {@code docs/matching-design.md}. No
 * discrepancy between the three was found for this table.
 *
 * <p>A join row with its own independently generated {@code id} (not a
 * shared-primary-key one-to-one) linking one {@link JobMatch} to one
 * canonical {@link Skill}. Both relationships are unidirectional
 * ({@code JobMatchSkill -> JobMatch}, {@code JobMatchSkill -> Skill});
 * neither {@link JobMatch} nor {@link Skill} is modified to add a
 * collection back-reference.
 *
 * <p>{@code job_match_id} cascades on delete ({@code ON DELETE CASCADE} -
 * a match's skill-level breakdown goes with it) while {@code skill_id}
 * restricts ({@code ON DELETE RESTRICT} - a canonical skill can't be
 * deleted while any match breakdown still references it), the same
 * asymmetry already present in the migration and mirrored by
 * {@link com.autoapplyjobs.platform.job.JobSkill} and
 * {@link com.autoapplyjobs.platform.resume.ResumeSkill}.
 *
 * <p><strong>Skill resolution note:</strong> this entity references the
 * already-canonical {@link Skill} row directly. It does not depend on
 * {@link com.autoapplyjobs.platform.job.SkillAlias} and implements no
 * alias-resolution or matching-calculation logic - alias resolution is
 * expected to have already happened before a {@code JobMatchSkill} row is
 * ever constructed.
 *
 * <p><strong>Identity/mutability - {@code (job_match_id, skill_id)}:
 * </strong> a {@code JobMatchSkill} represents one canonical {@link Skill}
 * within one specific {@link JobMatch}; the semantic identity of that
 * association is the pair {@code (job_match_id, skill_id)} itself, the
 * same reasoning already applied to
 * {@link com.autoapplyjobs.platform.job.JobSkill}. {@code id},
 * {@code jobMatch}, and {@code skill} are therefore all
 * {@code updatable = false} - a row is never mutated from one
 * {@link JobMatch} or {@link Skill} into another. When a future
 * recalculation changes which skills are represented for a
 * {@link JobMatch}, the service is expected to reconcile associations
 * (retain matching rows and update their result fields, delete rows for
 * skills no longer relevant, insert rows for newly relevant skills)
 * rather than repoint an existing row's {@code jobMatch}/{@code skill} -
 * no such reconciliation logic is implemented in this step.
 *
 * <p><strong>Mutable result fields:</strong> {@code matchStatus} and
 * {@code weight} represent the matching result for a retained
 * association, not identity - both are JPA-updatable, since the same
 * {@link JobMatch} may be recalculated (mirrors how {@link JobMatch}'s
 * own score fields are updatable while its identity fields are not).
 *
 * <p>{@code matchStatus} maps to {@link MatchStatus} via
 * {@code @Enumerated(EnumType.STRING)}, mirroring the migration's
 * {@code chk_job_match_skill_status} CHECK constraint - never a native
 * Postgres {@code ENUM} type.
 *
 * <p><strong>{@code weight} semantics (resolved after a repository-wide
 * audit found no prior definition; enforced by
 * {@code V5__constrain_job_match_skill_weight.sql}):</strong>
 * {@code weight} is a <em>relative skill-importance coefficient</em>,
 * used by a matching algorithm when aggregating individual skill-match
 * results into {@code JobMatch.skillsScore}. It is explicitly
 * <strong>not</strong>:
 * <ul>
 *   <li>a match-quality percentage - that is {@link #matchStatus}</li>
 *   <li>a {@code 0.00}-{@code 100.00} score like {@link JobMatch}'s own
 *       score columns (constrained by
 *       {@code V4__constrain_job_match_scores.sql})</li>
 *   <li>the already-weighted contribution to {@code overallScore}</li>
 *   <li>the same thing as
 *       {@link com.autoapplyjobs.platform.job.JobSkill#isRequired()}</li>
 * </ul>
 * Rules:
 * <ul>
 *   <li>Nullable. {@code NULL} means no explicit per-skill weight was
 *       persisted; the relevant algorithm version may apply its own
 *       default during calculation.</li>
 *   <li>When present, {@code weight} must be {@code >= 0} - enforced by
 *       {@code chk_job_match_skill_weight_non_negative} - since negative
 *       relative importance has no valid domain meaning.</li>
 *   <li>{@code 0.00} is valid: a skill can contribute zero relative
 *       weight while still being representable for
 *       explanation/detail.</li>
 *   <li>Positive values are relative coefficients; their magnitude only
 *       has meaning relative to other per-skill weights interpreted by
 *       the <em>same</em> algorithm version. Weights for one
 *       {@link JobMatch} are <strong>not</strong> required to sum to
 *       {@code 1}, are <strong>not</strong> required to sum to
 *       {@code 100}, and {@code weight} is not a percentage - so, unlike
 *       {@link JobMatch}'s scores, there is no <strong>domain-semantic</strong>
 *       upper bound such as {@code 1} or {@code 100}. This is distinct
 *       from the column's ordinary representational limit: {@code weight}
 *       remains {@code NUMERIC(5,2)}, which still permits at most 3
 *       digits before the decimal point. A value such as {@code 250.00}
 *       is therefore valid, while a value such as {@code 1000.00} remains
 *       invalid - not because of the {@code weight >= 0} semantic
 *       {@code CHECK} constraint, but because it exceeds what
 *       {@code NUMERIC(5,2)} can represent.</li>
 * </ul>
 * How {@link MatchStatus} converts to a numeric value, and how
 * {@link com.autoapplyjobs.platform.job.JobSkill#isRequired()} might
 * influence a chosen {@code weight}, are future matching-algorithm
 * decisions - neither is implemented or hard-coded as a direct mapping
 * in persistence.
 */
@Entity
@Table(
        name = "job_match_skill",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_job_match_skill_match_skill",
                columnNames = {"job_match_id", "skill_id"}
        )
)
public class JobMatchSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Fixed once set - see the class Javadoc. One half of this row's own
     * identity pair ({@code job_match_id, skill_id}). Not reassigned to a
     * different {@link JobMatch}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_match_id", nullable = false, updatable = false)
    private JobMatch jobMatch;

    /**
     * Fixed once set - see the class Javadoc. The other half of this
     * row's identity pair. Not reassigned to a different {@link Skill};
     * correcting a match's skill breakdown is modeled by reconciling
     * which {@code JobMatchSkill} rows exist, not by mutating one row's
     * skill.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false, updatable = false)
    private Skill skill;

    /** Mutable matching result for this association - see the class Javadoc. */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false)
    private MatchStatus matchStatus;

    /**
     * Mutable matching result for this association - see the class
     * Javadoc's {@code weight} semantics. A relative skill-importance
     * coefficient, not a percentage; nullable, and {@code >= 0} when
     * present (enforced by {@code chk_job_match_skill_weight_non_negative}).
     */
    @Column(name = "weight", precision = 5, scale = 2)
    private BigDecimal weight;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected JobMatchSkill() {
    }

    public JobMatchSkill(JobMatch jobMatch, Skill skill, MatchStatus matchStatus, BigDecimal weight) {
        this.jobMatch = jobMatch;
        this.skill = skill;
        this.matchStatus = matchStatus;
        this.weight = weight;
    }

    public UUID getId() {
        return id;
    }

    public JobMatch getJobMatch() {
        return jobMatch;
    }

    public Skill getSkill() {
        return skill;
    }

    public MatchStatus getMatchStatus() {
        return matchStatus;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobMatchSkill other)) {
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
        return "JobMatchSkill{id=" + id + ", matchStatus=" + matchStatus + "}";
    }
}
