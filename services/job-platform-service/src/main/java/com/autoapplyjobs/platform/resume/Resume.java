package com.autoapplyjobs.platform.resume;

import com.autoapplyjobs.platform.user.User;
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
 * Maps to the {@code resume} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p><strong>Immutability rule (clarified):</strong> a resume version's
 * content and identity are immutable after creation - {@code user},
 * {@code versionNumber}, {@code fileUrl}, {@code originalFilename}, and
 * {@code uploadedAt} never change, and there are no setters for them.
 * {@code isDefault} is mutable metadata and the one sanctioned exception -
 * see {@link #markAsDefault()} / {@link #removeAsDefault()}. Changing which
 * resume is default does not create a new resume version, and it never
 * changes which resume version a historical {@code application} row
 * references ({@code application.resume_id} keeps pointing at the exact
 * version used at application time regardless of later default changes).
 * Service-level logic that switches defaults transactionally across two
 * rows and publishes {@code resume.default.changed} is not part of this
 * mapping - only the entity-level operation is provided here.
 *
 * <p>{@code id} and {@code created_at} are database-generated
 * ({@code gen_random_uuid()} / {@code DEFAULT now()}), mapped the same way
 * as on {@link User}. {@code uploaded_at}, unlike {@code created_at}, has
 * <em>no</em> database default in the migration - it must be supplied by
 * the caller, so it is mapped as a normal insertable column, not
 * {@link Generated}.
 *
 * <p>{@code uq_resume_user_version} (unique on {@code user_id,
 * version_number}) is declared below via {@link UniqueConstraint} for
 * documentation - it has no effect at runtime since Hibernate schema
 * generation is disabled ({@code ddl-auto=none}). The partial unique index
 * enforcing "at most one default resume per user"
 * ({@code uq_resume_user_default ... WHERE is_default = true}) has no JPA
 * equivalent at all - {@code @UniqueConstraint} cannot express a
 * filtered/partial index - so it is enforced purely by PostgreSQL and only
 * documented here, not declared.
 */
@Entity
@Table(
        name = "resume",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_resume_user_version",
                columnNames = {"user_id", "version_number"}
        )
)
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "file_url", nullable = false, updatable = false)
    private String fileUrl;

    @Column(name = "original_filename", updatable = false)
    private String originalFilename;

    /**
     * The one mutable field on this entity - see {@link #markAsDefault()}
     * / {@link #removeAsDefault()} and the class Javadoc. No database
     * default is duplicated here: {@code false} is simply Java's own
     * primitive default, used because most resumes aren't the default -
     * not an attempt to mirror the DB's {@code DEFAULT false}.
     */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Resume() {
    }

    public Resume(User user, int versionNumber, String fileUrl, String originalFilename,
                  boolean isDefault, Instant uploadedAt) {
        this.user = user;
        this.versionNumber = versionNumber;
        this.fileUrl = fileUrl;
        this.originalFilename = originalFilename;
        this.isDefault = isDefault;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public boolean isDefault() {
        return isDefault;
    }

    /**
     * Marks this resume as its user's default. Does not create a new
     * resume version - it flips {@code isDefault} on this existing row.
     * This method does not itself unset any other resume's default flag
     * or enforce the one-default-per-user rule; PostgreSQL's
     * {@code uq_resume_user_default} partial unique index is the actual
     * enforcement, and a caller that marks two resumes for the same user
     * as default will have the second flush rejected by the database.
     * Coordinating that transactionally across two rows, and publishing
     * {@code resume.default.changed}, belongs to service-level logic that
     * doesn't exist yet.
     */
    public void markAsDefault() {
        this.isDefault = true;
    }

    /**
     * Removes this resume as its user's default (typically because
     * another resume is becoming the new default). See
     * {@link #markAsDefault()}.
     */
    public void removeAsDefault() {
        this.isDefault = false;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Resume other)) {
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
        return "Resume{id=" + id + ", versionNumber=" + versionNumber + ", isDefault=" + isDefault + "}";
    }
}
