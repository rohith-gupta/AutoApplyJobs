package com.autoapplyjobs.platform.job;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link Skill} JPA mapping - including the uniqueness of
 * {@code normalized_name} - against the real PostgreSQL schema created by
 * {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SkillEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsASkill() {
        Skill skill = new Skill("Python", "python");

        Skill persisted = entityManager.persistAndFlush(skill);

        assertThat(persisted.getId()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Skill skill = new Skill("PostgreSQL", "postgresql");
        UUID id = entityManager.persistAndFlush(skill).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        Skill reloaded = entityManager.find(Skill.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("PostgreSQL");
        assertThat(reloaded.getNormalizedName()).isEqualTo("postgresql");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateNormalizedNameFails() {
        entityManager.persistAndFlush(new Skill("Kubernetes", "kubernetes"));

        // Different display name, same normalized_name - exactly the
        // scenario uq_skill_normalized_name exists to prevent.
        Skill duplicate = new Skill("K8s", "kubernetes");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void distinctNormalizedNameValuesSucceed() {
        Skill first = entityManager.persistAndFlush(new Skill("Java", "java"));
        Skill second = entityManager.persistAndFlush(new Skill("JavaScript", "javascript"));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Skill skill = new Skill("Go", "go");

        Skill persisted = entityManager.persistAndFlush(skill);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }
}
