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
 * Maps to the {@code job_location} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p><strong>Canonical job data, like {@link Job} itself</strong> - not
 * treated as immutable by habit. Location content fields
 * ({@code city}, {@code stateRegion}, {@code country}, {@code postalCode},
 * {@code isRemote}, {@code rawLocationText}) are JPA-updatable: a job's
 * location may be corrected or enriched as the same posting is
 * re-processed, the same reasoning applied to {@link Job}'s own canonical
 * fields. {@code jobId} is treated as fixed once set - nothing in the
 * schema or design suggests a location row is ever reassigned to a
 * <em>different</em> job (unlike {@code Job.company}, which the design
 * explicitly clarified as correctable) - so the {@code job} association is
 * non-updatable. No setters or update/enrichment logic are added in this
 * step; this only makes the JPA metadata correct for when such logic
 * exists.
 *
 * <p><strong>Note:</strong> {@code uq_job_location_job_city_state_country_remote}
 * covers exactly the same columns ({@code job_id, city, state_region,
 * country, is_remote}) that are marked updatable here (all but
 * {@code job_id}). This is the same pattern already established on
 * {@link Job} (e.g. {@code requisition_id} vs.
 * {@code uq_job_company_requisition}): correcting a location's content to
 * match another existing location row for the same job is rejected by
 * PostgreSQL on UPDATE, not just INSERT - this step's tests exercise the
 * insert-time case; the update-time case follows the same enforcement
 * mechanism and is not re-tested separately for this constraint.
 *
 * <p>The unique constraint is declared below via {@link UniqueConstraint}
 * for documentation only (no runtime effect - Hibernate schema generation
 * is disabled, {@code ddl-auto=none}); the real enforcement is
 * PostgreSQL's. <strong>As of {@code V2__fix_job_location_uniqueness.sql}
 * </strong>, that constraint uses PostgreSQL 15+'s {@code UNIQUE NULLS NOT
 * DISTINCT}, so two {@code NULL} values in {@code city}/{@code
 * state_region} are treated as equal for uniqueness purposes. V1 originally
 * used the SQL-standard default (NULL never equals NULL), which integration
 * tests confirmed let logically-duplicate rows through whenever
 * {@code city} and/or {@code state_region} was {@code NULL} - see
 * {@code docs/database-design.md}'s "History" note on this table. The
 * constraint's name and columns are unchanged by V2, so the
 * {@code @UniqueConstraint} annotation below still names it accurately;
 * JPA has no vocabulary for {@code NULLS NOT DISTINCT} either way, so that
 * refinement is documented here in prose only, never expressed in the
 * annotation itself.
 *
 * <p>The relationship is unidirectional ({@code JobLocation -> Job} only)
 * - {@link Job} is not modified to add a collection back-reference.
 */
@Entity
@Table(
        name = "job_location",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_job_location_job_city_state_country_remote",
                columnNames = {"job_id", "city", "state_region", "country", "is_remote"}
        )
)
public class JobLocation {

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

    @Column(name = "city")
    private String city;

    @Column(name = "state_region")
    private String stateRegion;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "is_remote", nullable = false)
    private boolean isRemote;

    @Column(name = "raw_location_text")
    private String rawLocationText;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected JobLocation() {
    }

    public JobLocation(Job job, String city, String stateRegion, String country,
                        String postalCode, boolean isRemote, String rawLocationText) {
        this.job = job;
        this.city = city;
        this.stateRegion = stateRegion;
        this.country = country;
        this.postalCode = postalCode;
        this.isRemote = isRemote;
        this.rawLocationText = rawLocationText;
    }

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public String getCity() {
        return city;
    }

    public String getStateRegion() {
        return stateRegion;
    }

    public String getCountry() {
        return country;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public boolean isRemote() {
        return isRemote;
    }

    public String getRawLocationText() {
        return rawLocationText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JobLocation other)) {
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
        return "JobLocation{id=" + id + ", city=" + city + ", country=" + country + "}";
    }
}
