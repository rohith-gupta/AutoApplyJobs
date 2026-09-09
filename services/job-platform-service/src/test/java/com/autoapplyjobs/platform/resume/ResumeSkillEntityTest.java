package com.autoapplyjobs.platform.resume;

import com.autoapplyjobs.platform.job.Skill;
import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link ResumeSkill} JPA mapping - including its
 * relationships to {@link Resume} and {@link Skill} and the
 * {@code (resume_id, skill_id)} uniqueness rule - against the real
 * PostgreSQL schema created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResumeSkillEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    private Resume persistResume(User user, int versionNumber) {
        return entityManager.persistAndFlush(
                new Resume(user, versionNumber, "file://r" + versionNumber, null, false, Instant.now()));
    }

    private Skill persistSkill(String name, String normalizedName) {
        return entityManager.persistAndFlush(new Skill(name, normalizedName));
    }

    @Test
    void persistsForAnExistingResumeAndSkill() {
        User user = persistUser("resume-skill-test-1@example.com");
        Resume resume = persistResume(user, 1);
        Skill skill = persistSkill("Python", "python");

        ResumeSkill resumeSkill = new ResumeSkill(resume, skill, "EXPERT", new BigDecimal("5.5"));

        ResumeSkill persisted = entityManager.persistAndFlush(resumeSkill);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        User user = persistUser("resume-skill-test-2@example.com");
        Resume resume = persistResume(user, 1);
        Skill skill = persistSkill("Python", "python");
        ResumeSkill resumeSkill = new ResumeSkill(resume, skill, "EXPERT", new BigDecimal("5.5"));
        UUID id = entityManager.persistAndFlush(resumeSkill).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        ResumeSkill reloaded = entityManager.find(ResumeSkill.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getProficiency()).isEqualTo("EXPERT");
        assertThat(reloaded.getYearsOfExperience()).isEqualByComparingTo("5.5");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void resumeRelationshipResolvesCorrectly() {
        User user = persistUser("resume-skill-test-3@example.com");
        Resume resume = persistResume(user, 1);
        Skill skill = persistSkill("Python", "python");
        ResumeSkill resumeSkill = new ResumeSkill(resume, skill, null, null);
        UUID id = entityManager.persistAndFlush(resumeSkill).getId();

        entityManager.clear();
        ResumeSkill reloaded = entityManager.find(ResumeSkill.class, id);

        assertThat(reloaded.getResume()).isNotNull();
        assertThat(reloaded.getResume().getId()).isEqualTo(resume.getId());
        assertThat(reloaded.getResume().getVersionNumber()).isEqualTo(1);
    }

    @Test
    void skillRelationshipResolvesCorrectly() {
        User user = persistUser("resume-skill-test-4@example.com");
        Resume resume = persistResume(user, 1);
        Skill skill = persistSkill("Python", "python");
        ResumeSkill resumeSkill = new ResumeSkill(resume, skill, null, null);
        UUID id = entityManager.persistAndFlush(resumeSkill).getId();

        entityManager.clear();
        ResumeSkill reloaded = entityManager.find(ResumeSkill.class, id);

        assertThat(reloaded.getSkill()).isNotNull();
        assertThat(reloaded.getSkill().getId()).isEqualTo(skill.getId());
        assertThat(reloaded.getSkill().getNormalizedName()).isEqualTo("python");
    }

    @Test
    void violatesForeignKeyWithoutAValidResume() {
        Skill skill = persistSkill("Python", "python");
        // A proxy for a Resume id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Resume nonExistentResume = entityManager.getEntityManager()
                .getReference(Resume.class, UUID.randomUUID());
        ResumeSkill orphan = new ResumeSkill(nonExistentResume, skill, null, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void violatesForeignKeyWithoutAValidSkill() {
        User user = persistUser("resume-skill-test-6@example.com");
        Resume resume = persistResume(user, 1);
        // A proxy for a Skill id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Skill nonExistentSkill = entityManager.getEntityManager()
                .getReference(Skill.class, UUID.randomUUID());
        ResumeSkill orphan = new ResumeSkill(resume, nonExistentSkill, null, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void duplicateResumeAndSkillFails() {
        User user = persistUser("resume-skill-test-7@example.com");
        Resume resume = persistResume(user, 1);
        Skill skill = persistSkill("Python", "python");
        entityManager.persistAndFlush(new ResumeSkill(resume, skill, "BEGINNER", null));

        ResumeSkill duplicate = new ResumeSkill(resume, skill, "EXPERT", new BigDecimal("3.0"));

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleDifferentSkillsForTheSameResumeSucceed() {
        User user = persistUser("resume-skill-test-8@example.com");
        Resume resume = persistResume(user, 1);
        Skill python = persistSkill("Python", "python");
        Skill java = persistSkill("Java", "java");

        ResumeSkill first = entityManager.persistAndFlush(new ResumeSkill(resume, python, null, null));
        ResumeSkill second = entityManager.persistAndFlush(new ResumeSkill(resume, java, null, null));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void theSameSkillAttachedToDifferentResumeVersionsSucceeds() {
        User user = persistUser("resume-skill-test-9@example.com");
        Resume resumeV1 = persistResume(user, 1);
        Resume resumeV2 = persistResume(user, 2);
        Skill skill = persistSkill("Python", "python");

        ResumeSkill onV1 = entityManager.persistAndFlush(new ResumeSkill(resumeV1, skill, null, null));
        ResumeSkill onV2 = entityManager.persistAndFlush(new ResumeSkill(resumeV2, skill, null, null));

        assertThat(onV1.getResume().getId()).isEqualTo(resumeV1.getId());
        assertThat(onV2.getResume().getId()).isEqualTo(resumeV2.getId());
        assertThat(onV1.getSkill().getId()).isEqualTo(onV2.getSkill().getId());
    }

    @Test
    void nullableFieldsBehaveAccordingToTheSchema() {
        User user = persistUser("resume-skill-test-10@example.com");
        Resume resume = persistResume(user, 1);
        Skill skill = persistSkill("Python", "python");

        // proficiency and years_of_experience are both nullable.
        ResumeSkill minimal = new ResumeSkill(resume, skill, null, null);
        UUID id = entityManager.persistAndFlush(minimal).getId();

        entityManager.clear();
        ResumeSkill reloaded = entityManager.find(ResumeSkill.class, id);

        assertThat(reloaded.getProficiency()).isNull();
        assertThat(reloaded.getYearsOfExperience()).isNull();
        // Required associations are still present:
        assertThat(reloaded.getResume().getId()).isEqualTo(resume.getId());
        assertThat(reloaded.getSkill().getId()).isEqualTo(skill.getId());
    }
}
