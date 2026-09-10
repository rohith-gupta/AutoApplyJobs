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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code job_source} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth,
 * untouched by {@code V2}) and described in
 * {@code docs/database-design.md}.
 *
 * <p><strong>External job identity - two partial unique indexes, neither
 * declared via {@code @UniqueConstraint}:</strong>
 * <ul>
 *   <li>{@code uq_job_source_native_id}: {@code UNIQUE (source_name,
 *       source_native_id) WHERE source_native_id IS NOT NULL}</li>
 *   <li>{@code uq_job_source_url}: {@code UNIQUE (source_name, source_url)
 *       WHERE source_native_id IS NULL}</li>
 * </ul>
 * Together these represent one external posting identity: prefer the
 * native ID when the source provides one, fall back to the URL when it
 * doesn't (see {@code docs/deduplication-design.md}, signal #2). JPA's
 * {@code @UniqueConstraint} has no {@code WHERE} clause and would
 * misrepresent either as unconditional, so - consistent with every other
 * partial index in this codebase ({@code Job}'s
 * {@code uq_job_company_requisition}, {@code JobLocation}'s pre-V2 rule) -
 * both are documented here only; PostgreSQL remains the sole enforcement
 * point, exercised directly by this step's tests.
 *
 * <p><strong>Mutability</strong> - {@code job} is canonical/correctable:
 * a {@code JobSource} may later need reassociation with a different,
 * already-canonical {@link Job} after a deduplication/canonicalization
 * correction (the same reasoning already applied to {@code Job.company}),
 * so it is JPA-updatable. {@code sourceNativeId} is canonical/enrichable:
 * a posting may initially be observed with no native ID and gain one once
 * the source provides it (mirrors {@code Job.requisitionId}), so it too is
 * updatable. {@code lastSeenAt} is explicitly mutable (re-observed on
 * every re-scrape); {@code firstSeenAt} is a historical fact fixed once
 * established, so it is non-updatable, the same treatment as
 * {@code Job.firstSeenAt}.
 *
 * <p><strong>{@code sourceName} and {@code sourceUrl} (clarified after the
 * initial mapping step):</strong>
 * <ul>
 *   <li>{@code sourceName} is <strong>immutable</strong>
 *       ({@code updatable = false}). It identifies the external
 *       source/provider namespace and participates in <em>both</em>
 *       partial unique indexes above - changing it would mean this row
 *       now represents a different external identity, not ordinary
 *       enrichment or correction, so it is treated the same as
 *       {@code id}/{@code firstSeenAt}: fixed once established.</li>
 *   <li>{@code sourceUrl} remains <strong>mutable</strong>. Upstream
 *       posting URLs can be corrected or changed by the source itself.
 *       When {@code sourceNativeId} is present, the native ID is the
 *       preferred identity and {@code uq_job_source_url} doesn't even
 *       apply to the row (see "native-ID precedence" below); when
 *       {@code sourceNativeId} is {@code NULL}, {@code sourceUrl} <em>is</em>
 *       the fallback identity, so a future service correcting it will need
 *       to treat that case carefully (e.g. confirm it isn't merging two
 *       genuinely different fallback-identity rows). No such service logic
 *       is implemented here - only the entity-level mapping.</li>
 * </ul>
 * Both columns participate in one of the two partial unique indexes above,
 * so correcting {@code sourceUrl} to collide with another existing row for
 * the same {@code job_id} scope is still rejected by PostgreSQL on UPDATE,
 * the same pattern already accepted for {@code Job.requisitionId}.
 *
 * <p>{@code updated_at} is mapped the same DB-generated-at-insert-only way
 * as everywhere else in this codebase, and remains the same known,
 * unresolved timestamp-maintenance gap already flagged on {@link Job} -
 * not solved here.
 *
 * <p>No setters or reassignment/enrichment service logic are added in this
 * step; this only makes the JPA metadata correct for when such logic
 * exists. The relationship is unidirectional ({@code JobSource -> Job}
 * only) - {@link Job} is not modified to add a collection back-reference.
 */
@Entity
@Table(name = "job_source")
public class JobSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Canonical/correctable - see the class Javadoc. JPA-updatable so a
     * future reassociation service can reassign this row to a different,
     * already-canonical {@link Job}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    /**
     * Immutable - see the class Javadoc. Identifies the external
     * source/provider namespace; participates in both partial unique
     * indexes, so changing it would represent a different external
     * identity, not ordinary correction.
     */
    @Column(name = "source_name", nullable = false, updatable = false)
    private String sourceName;

    /** Canonical/enrichable - see the class Javadoc. */
    @Column(name = "source_native_id")
    private String sourceNativeId;

    /**
     * Mutable/correctable - see the class Javadoc. Upstream posting URLs
     * may be corrected or changed; when {@code sourceNativeId} is
     * {@code NULL} this is the fallback external identity, so future
     * service logic correcting it must treat that case carefully.
     */
    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    /**
     * A fact fixed once established, not revised afterward - the same
     * treatment as {@code Job.firstSeenAt}. Non-updatable.
     */
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    /** Updated on every re-observation from this source. Mutable. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Mapped as DB-generated-at-insert-only, the same unresolved
     * timestamp-maintenance gap already flagged on {@link Job#updatedAt} -
     * not solved in this step.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected JobSource() {
    }

    public JobSource(Job job, String sourceName, String sourceNativeId, String sourceUrl,
                      Instant firstSeenAt, Instant lastSeenAt) {
        this.job = job;
        this.sourceName = sourceName;
        this.sourceNativeId = sourceNativeId;
        this.sourceUrl = sourceUrl;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public Job getJob() {
        return job;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getSourceNativeId() {
        return sourceNativeId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
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
        if (!(o instanceof JobSource other)) {
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
        return "JobSource{id=" + id + ", sourceName=" + sourceName + ", sourceUrl=" + sourceUrl + "}";
    }
}
