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
 * Maps to the {@code resume_experience} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>Unlike {@link ResumeProfile}, this is a plain foreign key relationship
 * (each row has its own independently generated {@code id}), not a
 * shared-primary-key one-to-one - a {@link Resume} can have many
 * {@code resume_experience} rows.
 *
 * <p><strong>Immutable</strong> parsed data, same posture as
 * {@link Resume}'s own content fields: no setters, every field fixed at
 * construction. No update/reparse behavior is introduced here.
 *
 * <p>{@code company_name} is free text, intentionally not a foreign key to
 * {@code company} - resume experience is self-reported, not sourced from
 * job postings (see the migration's own comment on this table).
 * {@code end_date} being {@code null} means "current position" per
 * {@code docs/database-design.md}.
 *
 * <p>The relationship is unidirectional ({@code ResumeExperience -> Resume}
 * only) - {@link Resume} is not modified to add a collection
 * back-reference.
 */
@Entity
@Table(name = "resume_experience")
public class ResumeExperience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false, updatable = false)
    private Resume resume;

    @Column(name = "company_name", nullable = false, updatable = false)
    private String companyName;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    /** {@code null} means "current position" - see the class Javadoc. */
    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Column(name = "description", updatable = false)
    private String description;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ResumeExperience() {
    }

    public ResumeExperience(Resume resume, String companyName, String title,
                             LocalDate startDate, LocalDate endDate, String description) {
        this.resume = resume;
        this.companyName = companyName;
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public Resume getResume() {
        return resume;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResumeExperience other)) {
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
        return "ResumeExperience{id=" + id + ", companyName=" + companyName + ", title=" + title + "}";
    }
}
