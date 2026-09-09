package com.autoapplyjobs.platform.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the {@code skill} table exactly as defined in
 * {@code db/migration/V1__initial_schema.sql} (the source of truth) and
 * described in {@code docs/database-design.md}.
 *
 * <p>Canonical taxonomy data: no setters, nothing here renames a skill or
 * reprocesses it after creation. This step deliberately does not create
 * {@code SkillAlias}, does not create the canonical self-alias row that
 * every {@code skill} is documented as eventually needing, and does not
 * build any alias-resolution logic - those are separate, later steps.
 *
 * <p>{@code normalized_name} is declared {@code UNIQUE} below via
 * {@link UniqueConstraint} for documentation, matching
 * {@code uq_skill_normalized_name} in the migration - like all such
 * declarations in this codebase it has no effect at runtime since
 * Hibernate schema generation is disabled ({@code ddl-auto=none}); the
 * real enforcement is PostgreSQL's constraint, exercised directly by this
 * step's tests.
 */
@Entity
@Table(
        name = "skill",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_skill_normalized_name",
                columnNames = {"normalized_name"}
        )
)
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false, updatable = false)
    private String normalizedName;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Skill() {
    }

    public Skill(String name, String normalizedName) {
        this.name = name;
        this.normalizedName = normalizedName;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Skill other)) {
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
        return "Skill{id=" + id + ", name=" + name + ", normalizedName=" + normalizedName + "}";
    }
}
