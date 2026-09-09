package com.autoapplyjobs.platform.resume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code resume_profile} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>{@code resume_id} is both the primary key and the foreign key to
 * {@code resume.id} - there is no independent id generator (the column has
 * no {@code DEFAULT} in the migration). This is the same "derived identity"
 * / shared-primary-key one-to-one pattern used for
 * {@link com.autoapplyjobs.platform.user.UserJobPreference} ↔
 * {@link com.autoapplyjobs.platform.user.User}: this entity's id is copied
 * from the associated {@link Resume}'s id via {@link MapsId} rather than
 * generated separately, which is also what makes "at most one profile per
 * resume" true at the database level - {@code resume_id} being the primary
 * key makes a second row for the same resume a primary-key violation.
 *
 * <p>The relationship is unidirectional ({@code ResumeProfile -> Resume}
 * only) - {@link Resume} is not modified to add a back-reference.
 *
 * <p>No setters: this entity is treated as immutable parsed content tied to
 * one immutable resume file, the same posture as {@link Resume}'s own
 * content fields. Nothing in the frozen schema requires this - see the
 * step's final report for this as a flagged, not fully settled, design
 * choice. {@code created_at} is database-generated at insert only
 * ({@code DEFAULT now()}), mapped the same way as elsewhere in this
 * package.
 */
@Entity
@Table(name = "resume_profile")
public class ResumeProfile {

    @Id
    private UUID resumeId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "resume_id")
    private Resume resume;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "headline")
    private String headline;

    @Column(name = "years_of_experience", precision = 4, scale = 1)
    private BigDecimal yearsOfExperience;

    @Column(name = "summary")
    private String summary;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ResumeProfile() {
    }

    /**
     * @param resume the owning resume - required, since this entity's id
     *               is derived from it ({@link MapsId}); every other
     *               column is nullable in the database.
     */
    public ResumeProfile(Resume resume, String fullName, String headline,
                          BigDecimal yearsOfExperience, String summary) {
        this.resume = resume;
        this.fullName = fullName;
        this.headline = headline;
        this.yearsOfExperience = yearsOfExperience;
        this.summary = summary;
    }

    public UUID getResumeId() {
        return resumeId;
    }

    public Resume getResume() {
        return resume;
    }

    public String getFullName() {
        return fullName;
    }

    public String getHeadline() {
        return headline;
    }

    public BigDecimal getYearsOfExperience() {
        return yearsOfExperience;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResumeProfile other)) {
            return false;
        }
        return resumeId != null && resumeId.equals(other.resumeId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ResumeProfile{resumeId=" + resumeId + "}";
    }
}
