package com.autoapplyjobs.platform.job;

import com.autoapplyjobs.platform.company.Company;
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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code job} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md} /
 * {@code docs/deduplication-design.md}.
 *
 * <p><strong>Job is canonical operational data, not an immutable
 * snapshot</strong> - unlike {@code resume}/{@code resume_*} rows in this
 * codebase, a {@code job} row is expected to be revised as new source
 * information arrives (Job Processing normalizes → deduplicates → upserts;
 * see {@code docs/architecture.md}'s {@code job.canonical.updated} event).
 * Fields are therefore JPA-{@code updatable} by default here; only fields
 * reasoned below to be true identity are {@code updatable = false}. No
 * setters or update business logic are added in this step - see the
 * step's final report for how that reasoning was applied field by field,
 * including which fields were judgment calls rather than certainties.
 *
 * <p><strong>{@code (company_id, requisition_id)} is the one true
 * DB-enforced identity signal</strong> - a <em>partial</em> unique index,
 * {@code WHERE requisition_id IS NOT NULL}. JPA's {@code @UniqueConstraint}
 * cannot express a {@code WHERE} clause and would misrepresent this as an
 * unconditional constraint, so - consistent with how
 * {@link com.autoapplyjobs.platform.resume.Resume}'s partial default-resume
 * index was handled - it is documented here only, not declared via
 * {@code @UniqueConstraint}. {@code dedup_fingerprint} and
 * {@code canonical_apply_url_hash} are candidate/strong deduplication
 * signals only, deliberately <strong>not</strong> unique at all (indexed
 * for lookup) - this step's tests prove duplicates of both are accepted.
 * See {@code docs/deduplication-design.md} for the full signal hierarchy;
 * no deduplication/canonicalization logic is implemented here.
 *
 * <p>{@code status} maps to {@link JobStatus} via
 * {@code @Enumerated(EnumType.STRING)}, mirroring the migration's
 * {@code chk_job_status} CHECK constraint - never a native Postgres
 * {@code ENUM} type.
 */
@Entity
@Table(name = "job")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Part of the job's identity tuple (with {@link #requisitionId}) - see
     * the class Javadoc. Treated as non-updatable on the reasoning that a
     * job's owning company is fixed once established, but this is a
     * judgment call, not a certainty the schema/design documents settle
     * explicitly - flagged in this step's final report.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "normalized_title", nullable = false)
    private String normalizedTitle;

    @Column(name = "description", nullable = false)
    private String description;

    /**
     * The other half of the job's identity tuple (with {@link #company}) -
     * see the class Javadoc. Also non-updatable on the same judgment-call
     * basis, flagged alongside {@link #company} in this step's final
     * report.
     */
    @Column(name = "requisition_id", updatable = false)
    private String requisitionId;

    @Column(name = "canonical_apply_url")
    private String canonicalApplyUrl;

    /** Candidate signal - indexed, deliberately NOT unique (see class Javadoc). */
    @Column(name = "canonical_apply_url_hash")
    private String canonicalApplyUrlHash;

    @Column(name = "employment_type")
    private String employmentType;

    @Column(name = "remote_type")
    private String remoteType;

    @Column(name = "salary_min", precision = 12, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 12, scale = 2)
    private BigDecimal salaryMax;

    @Column(name = "salary_currency", length = 3)
    private String salaryCurrency;

    @Column(name = "experience_min_years", precision = 4, scale = 1)
    private BigDecimal experienceMinYears;

    @Column(name = "experience_max_years", precision = 4, scale = 1)
    private BigDecimal experienceMaxYears;

    /** Candidate signal - indexed, deliberately NOT unique (see class Javadoc). */
    @Column(name = "dedup_fingerprint", nullable = false)
    private String dedupFingerprint;

    /** As reported by source - may be corrected as source data is re-processed. */
    @Column(name = "posted_at")
    private Instant postedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    /**
     * "Earliest time any source reported this job" - a fact fixed once
     * established, not revised afterward. Non-updatable.
     */
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    /**
     * Updated every time the job is re-observed from any source - this is
     * the field's entire purpose (see {@code docs/database-design.md}'s
     * first_seen_at/last_seen_at/expiration tracking). Definitely mutable.
     */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Mapped as DB-generated-at-insert-only ({@code DEFAULT now()}, no
     * update trigger exists), the same as every other {@code updated_at}
     * in this codebase - <strong>not</strong> because this field is
     * believed immutable (it isn't - {@code Job} is explicitly mutable
     * operational data) but because that is genuinely all the current
     * schema does. See this step's final report: unlike everywhere else
     * this pattern has been used, this is flagged as a real gap, not a
     * settled choice - once update logic exists, correctly maintaining
     * this column will need either a DB trigger (a new migration) or
     * application code explicitly setting it on every save, and this
     * mapping does neither yet.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Job() {
    }

    public Job(Company company, String title, String normalizedTitle, String description,
               String requisitionId, String canonicalApplyUrl, String canonicalApplyUrlHash,
               String employmentType, String remoteType, BigDecimal salaryMin, BigDecimal salaryMax,
               String salaryCurrency, BigDecimal experienceMinYears, BigDecimal experienceMaxYears,
               String dedupFingerprint, Instant postedAt, JobStatus status,
               Instant firstSeenAt, Instant lastSeenAt, Instant expiresAt) {
        this.company = company;
        this.title = title;
        this.normalizedTitle = normalizedTitle;
        this.description = description;
        this.requisitionId = requisitionId;
        this.canonicalApplyUrl = canonicalApplyUrl;
        this.canonicalApplyUrlHash = canonicalApplyUrlHash;
        this.employmentType = employmentType;
        this.remoteType = remoteType;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.salaryCurrency = salaryCurrency;
        this.experienceMinYears = experienceMinYears;
        this.experienceMaxYears = experienceMaxYears;
        this.dedupFingerprint = dedupFingerprint;
        this.postedAt = postedAt;
        this.status = status;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getTitle() {
        return title;
    }

    public String getNormalizedTitle() {
        return normalizedTitle;
    }

    public String getDescription() {
        return description;
    }

    public String getRequisitionId() {
        return requisitionId;
    }

    public String getCanonicalApplyUrl() {
        return canonicalApplyUrl;
    }

    public String getCanonicalApplyUrlHash() {
        return canonicalApplyUrlHash;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public String getRemoteType() {
        return remoteType;
    }

    public BigDecimal getSalaryMin() {
        return salaryMin;
    }

    public BigDecimal getSalaryMax() {
        return salaryMax;
    }

    public String getSalaryCurrency() {
        return salaryCurrency;
    }

    public BigDecimal getExperienceMinYears() {
        return experienceMinYears;
    }

    public BigDecimal getExperienceMaxYears() {
        return experienceMaxYears;
    }

    public String getDedupFingerprint() {
        return dedupFingerprint;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
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
        if (!(o instanceof Job other)) {
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
        return "Job{id=" + id + ", title=" + title + ", status=" + status + "}";
    }
}
