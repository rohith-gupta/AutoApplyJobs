package com.autoapplyjobs.platform.user;

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
 * Maps to the {@code user_job_preference} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>{@code user_id} is both the primary key and the foreign key to
 * {@code app_user.id} - there is no independent id generator (the column
 * has no {@code DEFAULT} in the migration). This is modeled as a JPA
 * "derived identity" / shared-primary-key one-to-one via {@link MapsId}:
 * this entity's id is copied from the associated {@link User}'s id rather
 * than generated separately, which is also what naturally enforces "at
 * most one preference row per user" - {@code user_id} being the primary
 * key makes a second row for the same user a primary-key violation.
 *
 * <p>{@code created_at} / {@code updated_at} follow the same DB-generated,
 * insert-only mapping used on {@link User}, for the same reason: the
 * migration gives both an insert-time {@code DEFAULT now()} only, with no
 * update trigger, so nothing here duplicates that default or invents
 * update-tracking behavior the schema doesn't have.
 */
@Entity
@Table(name = "user_job_preference")
public class UserJobPreference {

    @Id
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    /** TEXT[] in PostgreSQL - mapped as a native Java array, not a List. */
    @Column(name = "preferred_locations", columnDefinition = "text[]")
    private String[] preferredLocations;

    @Column(name = "workplace_type_preference", columnDefinition = "text[]")
    private String[] workplaceTypePreference;

    @Column(name = "employment_type_preference", columnDefinition = "text[]")
    private String[] employmentTypePreference;

    @Column(name = "salary_min_preference", precision = 12, scale = 2)
    private BigDecimal salaryMinPreference;

    /** CHAR(3) in PostgreSQL (ISO 4217 currency code). */
    @Column(name = "salary_currency", length = 3)
    private String salaryCurrency;

    /**
     * Boolean wrapper, not primitive: BOOLEAN is nullable in the database
     * and "not specified" is a real, distinct state from "false".
     */
    @Column(name = "sponsorship_required")
    private Boolean sponsorshipRequired;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected UserJobPreference() {
    }

    /**
     * @param user the owning user - required, since this entity's id is
     *             derived from it ({@link MapsId}); every other column is
     *             nullable in the database and left unset here.
     */
    public UserJobPreference(User user) {
        this.user = user;
    }

    public UUID getUserId() {
        return userId;
    }

    public User getUser() {
        return user;
    }

    public String[] getPreferredLocations() {
        return preferredLocations;
    }

    public void setPreferredLocations(String[] preferredLocations) {
        this.preferredLocations = preferredLocations;
    }

    public String[] getWorkplaceTypePreference() {
        return workplaceTypePreference;
    }

    public void setWorkplaceTypePreference(String[] workplaceTypePreference) {
        this.workplaceTypePreference = workplaceTypePreference;
    }

    public String[] getEmploymentTypePreference() {
        return employmentTypePreference;
    }

    public void setEmploymentTypePreference(String[] employmentTypePreference) {
        this.employmentTypePreference = employmentTypePreference;
    }

    public BigDecimal getSalaryMinPreference() {
        return salaryMinPreference;
    }

    public void setSalaryMinPreference(BigDecimal salaryMinPreference) {
        this.salaryMinPreference = salaryMinPreference;
    }

    public String getSalaryCurrency() {
        return salaryCurrency;
    }

    public void setSalaryCurrency(String salaryCurrency) {
        this.salaryCurrency = salaryCurrency;
    }

    public Boolean getSponsorshipRequired() {
        return sponsorshipRequired;
    }

    public void setSponsorshipRequired(Boolean sponsorshipRequired) {
        this.sponsorshipRequired = sponsorshipRequired;
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
        if (!(o instanceof UserJobPreference other)) {
            return false;
        }
        return userId != null && userId.equals(other.userId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "UserJobPreference{userId=" + userId + "}";
    }
}
