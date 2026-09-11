package com.autoapplyjobs.platform.matching;

import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.resume.Resume;
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
 * Maps to the {@code job_match} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} as amended by
 * {@code V4__constrain_job_match_scores.sql} (the source of truth,
 * untouched by {@code V2}/{@code V3}) and described in
 * {@code docs/database-design.md} / {@code docs/matching-design.md}. No
 * discrepancy between the three was found for this table.
 *
 * <p><strong>Identity - {@code (job_id, resume_id, algorithm_version)}:
 * </strong> per {@code docs/matching-design.md}, a {@code JobMatch} row is
 * identified by exactly these three things. All three are fixed once the
 * row is created - {@code job}, {@code resume}, and
 * {@code algorithmVersion} are all {@code updatable = false}, alongside
 * {@code id}. A {@code JobMatch} is never mutated from one {@link Job},
 * {@link Resume}, or algorithm version into another; recomputing under a
 * new algorithm version means creating a <em>new</em> row for that
 * version (e.g. {@code MATCH_V1} and {@code MATCH_V2} coexist as separate
 * rows for the same job/resume pair), never rewriting
 * {@code algorithm_version} on an existing one - this is how the schema
 * preserves algorithm-version history (see
 * {@code docs/matching-design.md}'s "Algorithm evolution").
 *
 * <p><strong>Scores are mutable, derived operational data - not an
 * immutable snapshot:</strong> unlike a {@link Resume} version (immutable
 * content) or {@code application.job_match_score_at_application} (a
 * frozen point-in-time snapshot, mapped separately and not part of this
 * entity), a {@code JobMatch} row for a given
 * {@code (job_id, resume_id, algorithm_version)} may need
 * recalculation whenever the canonical {@link Job} is later enriched or
 * updated - the resume version itself never changes, but the job side of
 * the comparison can. Per {@code docs/matching-design.md} ("every
 * consumer is idempotent... recomputing writes to the same row via
 * upsert"), {@code overallScore}, every per-dimension sub-score
 * ({@code skillsScore}, {@code experienceScore}, {@code titleScore},
 * {@code responsibilityScore}, {@code educationScore},
 * {@code locationScore}, {@code preferenceScore}), and
 * {@code computedAt} are all JPA-updatable. No setters and no matching
 * calculation/recompute service logic are added in this step; this only
 * makes the JPA metadata correct for when such logic exists.
 *
 * <p>Unlike {@link Job}, {@link com.autoapplyjobs.platform.job.JobSource},
 * and {@link com.autoapplyjobs.platform.job.RawJobProcessing},
 * {@code job_match} has <strong>no {@code updated_at} column at all</strong>
 * in V1 - only {@code created_at} - so there is no
 * DB-generated-at-insert-only "known gap" to flag here the way there is
 * on those tables; the schema simply doesn't track a last-modified
 * timestamp for this row, only {@code computed_at} (an application-level
 * value the caller supplies, representing when the score itself was
 * computed - not a DB-generated audit column).
 *
 * <p>{@code overall_score} is required ({@code NOT NULL}); every
 * per-dimension sub-score is nullable - present only if the algorithm
 * version in question computes it, per
 * {@code docs/matching-design.md}'s "Score shape". All score columns are
 * {@code NUMERIC(5,2)}, mapped to {@link BigDecimal} with matching
 * {@code precision}/{@code scale} - never a floating-point type, per
 * {@code CLAUDE.md}.
 *
 * <p><strong>Score semantics (clarified after the initial mapping step,
 * enforced by {@code V4__constrain_job_match_scores.sql}):</strong> every
 * score - {@code overallScore} and all seven sub-scores - represents a
 * <em>normalized percentage in the range {@code 0.00} through
 * {@code 100.00} inclusive</em>, enforced by one database {@code CHECK}
 * constraint per column ({@code chk_job_match_<column>_range}). A
 * sub-score is the category's own normalized percentage, <strong>not</strong>
 * its already-weighted contribution to {@code overallScore} - future
 * matching weights (e.g. skills 40%, experience 20%, ...) are applied by
 * matching logic when computing {@code overallScore} from the sub-scores,
 * not baked into the sub-score values themselves; no weighting or
 * matching-algorithm logic is implemented here. A {@code NULL} sub-score
 * remains valid regardless of the range constraint - under standard SQL
 * three-valued logic a {@code NULL} comparison is {@code UNKNOWN}, not
 * {@code FALSE}, so PostgreSQL's {@code CHECK} does not reject it. This
 * range is documented here only, not expressible via JPA/Jakarta
 * Validation annotations on the entity - PostgreSQL's {@code CHECK}
 * constraints are the sole enforcement point, exercised directly by this
 * step's tests, consistent with how every other DB-level rule in this
 * codebase (partial unique indexes, {@code CHECK}-backed enums) is
 * treated: documented in Java, enforced by PostgreSQL, never duplicated
 * as a Bean Validation annotation merely as a substitute.
 *
 * <p>Both relationships are unidirectional ({@code JobMatch -> Job},
 * {@code JobMatch -> Resume}); neither {@link Job} nor {@link Resume} is
 * modified to add a collection back-reference. {@code job_id} and
 * {@code resume_id} both cascade on delete ({@code ON DELETE CASCADE}) -
 * a match row has no meaning once its job or resume version is gone.
 *
 * <p>{@code uq_job_match_job_resume_algorithm} (unique on {@code job_id,
 * resume_id, algorithm_version}) is declared below via
 * {@link UniqueConstraint} for documentation - it has no effect at
 * runtime since Hibernate schema generation is disabled
 * ({@code ddl-auto=none}); the real enforcement is PostgreSQL's
 * constraint, exercised directly by this step's tests.
 *
 * <p>No {@code JobMatchSkill}, repositories, services, controllers, DTOs,
 * or matching-calculation logic are added in this step.
 */
@Entity
@Table(
        name = "job_match",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_job_match_job_resume_algorithm",
                columnNames = {"job_id", "resume_id", "algorithm_version"}
        )
)
public class JobMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * One third of this row's identity - see the class Javadoc. Fixed
     * once set; never reassigned to a different {@link Job}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private Job job;

    /**
     * One third of this row's identity - see the class Javadoc. Fixed
     * once set; never reassigned to a different {@link Resume}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, updatable = false)
    private Resume resume;

    /**
     * One third of this row's identity - see the class Javadoc. Fixed
     * once set. A new algorithm version (e.g. {@code MATCH_V2} replacing
     * {@code MATCH_V1}) is a new {@code JobMatch} row, never a change to
     * this field on an existing one.
     */
    @Column(name = "algorithm_version", nullable = false, updatable = false)
    private String algorithmVersion;

    /** Mutable, recalculable - see the class Javadoc. Required. */
    @Column(name = "overall_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal overallScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "skills_score", precision = 5, scale = 2)
    private BigDecimal skillsScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "experience_score", precision = 5, scale = 2)
    private BigDecimal experienceScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "title_score", precision = 5, scale = 2)
    private BigDecimal titleScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "responsibility_score", precision = 5, scale = 2)
    private BigDecimal responsibilityScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "education_score", precision = 5, scale = 2)
    private BigDecimal educationScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "location_score", precision = 5, scale = 2)
    private BigDecimal locationScore;

    /** Mutable, recalculable - see the class Javadoc. Present only if this algorithm version computes it. */
    @Column(name = "preference_score", precision = 5, scale = 2)
    private BigDecimal preferenceScore;

    /**
     * Mutable, recalculable - see the class Javadoc. An application-level
     * value the caller supplies (when the score was computed), not a
     * database default - updated every time this row is recomputed.
     */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected JobMatch() {
    }

    public JobMatch(Job job, Resume resume, String algorithmVersion, BigDecimal overallScore,
                     BigDecimal skillsScore, BigDecimal experienceScore, BigDecimal titleScore,
                     BigDecimal responsibilityScore, BigDecimal educationScore, BigDecimal locationScore,
                     BigDecimal preferenceScore, Instant computedAt) {
        this.job = job;
        this.resume = resume;
        this.algorithmVersion = algorithmVersion;
        this.overallScore = overallScore;
        this.skillsScore = skillsScore;
        this.experienceScore = experienceScore;
        this.titleScore = titleScore;
        this.responsibilityScore = responsibilityScore;
        this.educationScore = educationScore;
        this.locationScore = locationScore;
        this.preferenceScore = preferenceScore;
        this.computedAt = computedAt;
    }

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public Resume getResume() {
        return resume;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public BigDecimal getOverallScore() {
        return overallScore;
    }

    public BigDecimal getSkillsScore() {
        return skillsScore;
    }

    public BigDecimal getExperienceScore() {
        return experienceScore;
    }

    public BigDecimal getTitleScore() {
        return titleScore;
    }

    public BigDecimal getResponsibilityScore() {
        return responsibilityScore;
    }

    public BigDecimal getEducationScore() {
        return educationScore;
    }

    public BigDecimal getLocationScore() {
        return locationScore;
    }

    public BigDecimal getPreferenceScore() {
        return preferenceScore;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobMatch other)) {
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
        return "JobMatch{id=" + id + ", algorithmVersion=" + algorithmVersion
                + ", overallScore=" + overallScore + "}";
    }
}
