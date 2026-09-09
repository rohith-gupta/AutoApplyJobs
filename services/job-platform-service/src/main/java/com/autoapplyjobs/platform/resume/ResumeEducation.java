package com.autoapplyjobs.platform.resume;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps to the {@code resume_education} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>Same shape as {@link ResumeExperience}: a plain foreign key
 * relationship (each row has its own independently generated {@code id}),
 * not a shared-primary-key one-to-one - a {@link Resume} can have many
 * {@code resume_education} rows.
 *
 * <p><strong>Note the nullability difference from {@link ResumeExperience}:
 * </strong> here {@code start_date} is nullable, not required - only
 * {@code institution_name} is {@code NOT NULL} on this table. {@code
 * end_date} being {@code null} means "ongoing" per
 * {@code docs/database-design.md} (the equivalent of
 * {@code ResumeExperience}'s "current position").
 *
 * <p><strong>Immutable</strong> parsed data, same posture as
 * {@link ResumeExperience}: no setters, every field fixed at construction,
 * {@code updatable = false} throughout. No reprocessing/update behavior is
 * introduced here.
 *
 * <p>The relationship is unidirectional ({@code ResumeEducation -> Resume}
 * only) - {@link Resume} is not modified to add a collection
 * back-reference.
 */
@Entity
@Table(name = "resume_education")
public class ResumeEducation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, updatable = false)
    private Resume resume;

    @Column(name = "institution_name", nullable = false, updatable = false)
    private String institutionName;

    @Column(name = "degree", updatable = false)
    private String degree;

    @Column(name = "field_of_study", updatable = false)
    private String fieldOfStudy;

    @Column(name = "start_date", updatable = false)
    private LocalDate startDate;

    /** {@code null} means "ongoing" - see the class Javadoc. */
    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ResumeEducation() {
    }

    public ResumeEducation(Resume resume, String institutionName, String degree,
                            String fieldOfStudy, LocalDate startDate, LocalDate endDate) {
        this.resume = resume;
        this.institutionName = institutionName;
        this.degree = degree;
        this.fieldOfStudy = fieldOfStudy;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public UUID getId() {
        return id;
    }

    public Resume getResume() {
        return resume;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public String getDegree() {
        return degree;
    }

    public String getFieldOfStudy() {
        return fieldOfStudy;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResumeEducation other)) {
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
        return "ResumeEducation{id=" + id + ", institutionName=" + institutionName + "}";
    }
}
