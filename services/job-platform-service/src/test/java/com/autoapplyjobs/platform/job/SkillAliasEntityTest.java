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
 * Verifies the {@link SkillAlias} JPA mapping - including its relationship
 * to {@link Skill} and the uniqueness of {@code normalized_alias} - against
 * the real PostgreSQL schema created by
 * {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SkillAliasEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private Skill persistSkill(String name, String normalizedName) {
        return entityManager.persistAndFlush(new Skill(name, normalizedName));
    }

    @Test
    void persistsForAnExistingSkill() {
        Skill skill = persistSkill("Kubernetes", "kubernetes");
        SkillAlias alias = new SkillAlias(skill, "K8s", "k8s");

        SkillAlias persisted = entityManager.persistAndFlush(alias);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Skill skill = persistSkill("Kubernetes", "kubernetes");
        SkillAlias alias = new SkillAlias(skill, "K8s", "k8s");
        UUID id = entityManager.persistAndFlush(alias).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        SkillAlias reloaded = entityManager.find(SkillAlias.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getAlias()).isEqualTo("K8s");
        assertThat(reloaded.getNormalizedAlias()).isEqualTo("k8s");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void skillRelationshipResolvesCorrectly() {
        Skill skill = persistSkill("Kubernetes", "kubernetes");
        SkillAlias alias = new SkillAlias(skill, "K8s", "k8s");
        UUID id = entityManager.persistAndFlush(alias).getId();

        entityManager.clear();
        SkillAlias reloaded = entityManager.find(SkillAlias.class, id);

        assertThat(reloaded.getSkill()).isNotNull();
        assertThat(reloaded.getSkill().getId()).isEqualTo(skill.getId());
        assertThat(reloaded.getSkill().getNormalizedName()).isEqualTo("kubernetes");
    }

    @Test
    void violatesForeignKeyWithoutAValidSkill() {
        // A proxy for a Skill id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Skill nonExistentSkill = entityManager.getEntityManager()
                .getReference(Skill.class, UUID.randomUUID());
        SkillAlias orphan = new SkillAlias(nonExistentSkill, "K8s", "k8s");

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleDifferentAliasesForTheSameSkillSucceed() {
        Skill skill = persistSkill("Kubernetes", "kubernetes");

        SkillAlias first = entityManager.persistAndFlush(new SkillAlias(skill, "K8s", "k8s"));
        SkillAlias second = entityManager.persistAndFlush(new SkillAlias(skill, "Kube", "kube"));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void duplicateNormalizedAliasFailsEvenAttachedToADifferentSkill() {
        Skill kubernetes = persistSkill("Kubernetes", "kubernetes");
        Skill kubecost = persistSkill("Kubecost", "kubecost");
        entityManager.persistAndFlush(new SkillAlias(kubernetes, "K8s", "k8s"));

        // Same normalized_alias, but attached to an entirely different
        // skill - global uniqueness must still reject it.
        SkillAlias conflicting = new SkillAlias(kubecost, "K8S", "k8s");

        assertThatThrownBy(() -> entityManager.persistAndFlush(conflicting))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void differentNormalizedAliasValuesSucceed() {
        Skill skill = persistSkill("JavaScript", "javascript");

        SkillAlias first = entityManager.persistAndFlush(new SkillAlias(skill, "JS", "js"));
        SkillAlias second = entityManager.persistAndFlush(new SkillAlias(skill, "ECMAScript", "ecmascript"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void aCanonicalSelfAliasCanBeStoredSuccessfully() {
        Skill skill = persistSkill("PostgreSQL", "postgresql");

        // alias/normalizedAlias mirror the skill's own name/normalizedName -
        // this is what the eventual self-alias workflow will produce, but
        // nothing automatic creates it here; this test only proves the
        // mapping accepts that shape of row when constructed explicitly.
        SkillAlias selfAlias = new SkillAlias(skill, "PostgreSQL", "postgresql");

        SkillAlias persisted = entityManager.persistAndFlush(selfAlias);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getAlias()).isEqualTo(skill.getName());
        assertThat(persisted.getNormalizedAlias()).isEqualTo(skill.getNormalizedName());
        assertThat(persisted.getSkill().getId()).isEqualTo(skill.getId());
    }
}
