package com.autoapplyjobs.platform.application;

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

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code application_status_history} table exactly as defined
 * in {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}. No discrepancy between the
 * two was found for this table.
 *
 * <p>The permanent, append-only audit trail of status changes on an
 * {@link Application}. Both V1 and the docs are explicit: no
 * {@code updated_at} column, rows are never modified after insert
 * ({@code CLAUDE.md} lists these rows among the immutable-by-design
 * records). So every column is {@code updatable = false} - the same
 * all-fields-fixed posture as {@code ResumeSkill}. This is enforced at the
 * JPA layer only; V1 adds no DB trigger/permission revoke (flagged there as
 * a future option), so a native {@code UPDATE} would still succeed.
 *
 * <p>The relationship is unidirectional ({@code ApplicationStatusHistory
 * -> Application}) and lazy; {@link Application} has no collection
 * back-reference. {@code application_id} is {@code ON DELETE CASCADE} in
 * V1 - but applications are archived, never hard-deleted, so in practice
 * that cascade only fires when the owning {@code app_user} is deleted.
 *
 * <p>{@code changedAt} is caller-supplied (no DB default) - when the status
 * change happened. {@code createdAt} is DB-generated ({@code DEFAULT
 * now()}) - when the row was recorded. Keeping
 * {@code Application.currentStatus} consistent with the latest history row
 * is application logic and is not implemented here.
 */
@Entity
@Table(name = "application_status_history")
public class ApplicationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private Application application;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private ApplicationStatus status;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "note", updatable = false)
    private String note;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ApplicationStatusHistory() {
    }

    public ApplicationStatusHistory(Application application, ApplicationStatus status, Instant changedAt,
                                    String note) {
        this.application = application;
        this.status = status;
        this.changedAt = changedAt;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }

    public Application getApplication() {
        return application;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApplicationStatusHistory other)) {
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
        return "ApplicationStatusHistory{id=" + id + ", status=" + status + ", changedAt=" + changedAt + "}";
    }
}
