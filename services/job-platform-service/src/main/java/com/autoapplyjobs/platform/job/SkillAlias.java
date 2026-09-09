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
 * Maps to the {@code skill_alias} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>A plain foreign key relationship (own independently generated
 * {@code id}), not a shared-primary-key one-to-one - a {@link Skill} can
 * have many {@code skill_alias} rows. Unidirectional
 * ({@code SkillAlias -> Skill} only); {@link Skill} is not modified to add
 * a collection back-reference.
 *
 * <p><strong>Canonical taxonomy data</strong>, same posture as
 * {@link Skill}: no setters, no rename/update behavior.
 *
 * <p><strong>Self-alias rule (documented, not implemented here):</strong>
 * every {@code skill} row is documented as eventually needing exactly one
 * corresponding {@code skill_alias} row where {@code normalized_alias}
 * equals {@code skill.normalized_name} - see {@code CLAUDE.md} and
 * {@code docs/database-design.md}. That invariant is application-enforced,
 * not a database constraint, and nothing here creates it automatically:
 * this entity can represent a self-alias if one is constructed with
 * matching values (see this step's tests), but no workflow, service, or
 * trigger creates one automatically yet.
 *
 * <p>{@code normalized_alias} is declared {@code UNIQUE} below via
 * {@link UniqueConstraint} for documentation, matching
 * {@code uq_skill_alias_normalized_alias} in the migration - like all such
 * declarations in this codebase it has no effect at runtime since
 * Hibernate schema generation is disabled ({@code ddl-auto=none}); the
 * real enforcement is PostgreSQL's constraint, exercised directly by this
 * step's tests. Notably, this uniqueness is global across all skills, not
 * scoped per-skill - two different {@link Skill}s can never claim the same
 * {@code normalized_alias}.
 */
@Entity
@Table(
        name = "skill_alias",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_skill_alias_normalized_alias",
                columnNames = {"normalized_alias"}
        )
)
public class SkillAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false, updatable = false)
    private Skill skill;

    @Column(name = "alias", nullable = false, updatable = false)
    private String alias;

    @Column(name = "normalized_alias", nullable = false, updatable = false)
    private String normalizedAlias;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected SkillAlias() {
    }

    public SkillAlias(Skill skill, String alias, String normalizedAlias) {
        this.skill = skill;
        this.alias = alias;
        this.normalizedAlias = normalizedAlias;
    }

    public UUID getId() {
        return id;
    }

    public Skill getSkill() {
        return skill;
    }

    public String getAlias() {
        return alias;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillAlias other)) {
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
        return "SkillAlias{id=" + id + ", alias=" + alias + ", normalizedAlias=" + normalizedAlias + "}";
    }
}
