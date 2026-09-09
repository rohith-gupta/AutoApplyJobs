package com.autoapplyjobs.platform.resume;

import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link ResumeExperience} JPA mapping - including its
 * relationship to {@link Resume} - against the real PostgreSQL schema
 * created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as the rest of this package's tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResumeExperienceEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    private Resume persistResume(User user, int versionNumber) {
        return entityManager.persistAndFlush(
                new Resume(user, versionNumber, "file://r" + versionNumber, null, false, Instant.now()));
    }

    @Test
    void persistsForAnExistingResume() {
        User user = persistUser("experience-test-1@example.com");
        Resume resume = persistResume(user, 1);
        ResumeExperience experience = new ResumeExperience(resume, "Acme Corp", "Backend Engineer",
                LocalDate.of(2020, 1, 15), LocalDate.of(2022, 6, 30), "Built things.");

        ResumeExperience persisted = entityManager.persistAndFlush(experience);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        User user = persistUser("experience-test-2@example.com");
        Resume resume = persistResume(user, 1);
        ResumeExperience experience = new ResumeExperience(resume, "Acme Corp", "Backend Engineer",
                LocalDate.of(2020, 1, 15), LocalDate.of(2022, 6, 30), "Built things.");
        UUID id = entityManager.persistAndFlush(experience).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        ResumeExperience reloaded = entityManager.find(ResumeExperience.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getCompanyName()).isEqualTo("Acme Corp");
        assertThat(reloaded.getTitle()).isEqualTo("Backend Engineer");
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(reloaded.getEndDate()).isEqualTo(LocalDate.of(2022, 6, 30));
        assertThat(reloaded.getDescription()).isEqualTo("Built things.");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void resumeRelationshipResolvesCorrectly() {
        User user = persistUser("experience-test-3@example.com");
        Resume resume = persistResume(user, 1);
        ResumeExperience experience = new ResumeExperience(resume, "Acme Corp", "Backend Engineer",
                LocalDate.of(2020, 1, 15), null, null);
        UUID id = entityManager.persistAndFlush(experience).getId();

        entityManager.clear();
        ResumeExperience reloaded = entityManager.find(ResumeExperience.class, id);

        assertThat(reloaded.getResume()).isNotNull();
        assertThat(reloaded.getResume().getId()).isEqualTo(resume.getId());
        assertThat(reloaded.getResume().getVersionNumber()).isEqualTo(1);
    }

    @Test
    void violatesForeignKeyWithoutAValidResume() {
        // A proxy for a Resume id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Resume nonExistentResume = entityManager.getEntityManager()
                .getReference(Resume.class, UUID.randomUUID());
        ResumeExperience orphan = new ResumeExperience(nonExistentResume, "Acme Corp", "Engineer",
                LocalDate.of(2020, 1, 1), null, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleExperienceRowsForTheSameResumeSucceed() {
        User user = persistUser("experience-test-5@example.com");
        Resume resume = persistResume(user, 1);

        ResumeExperience first = entityManager.persistAndFlush(new ResumeExperience(
                resume, "Acme Corp", "Engineer", LocalDate.of(2018, 1, 1), LocalDate.of(2020, 1, 1), null));
        ResumeExperience second = entityManager.persistAndFlush(new ResumeExperience(
                resume, "Globex Corp", "Senior Engineer", LocalDate.of(2020, 2, 1), null, null));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void experienceRowsForDifferentResumeVersionsSucceedIndependently() {
        User user = persistUser("experience-test-6@example.com");
        Resume resumeV1 = persistResume(user, 1);
        Resume resumeV2 = persistResume(user, 2);

        ResumeExperience expV1 = entityManager.persistAndFlush(new ResumeExperience(
                resumeV1, "Acme Corp", "Engineer", LocalDate.of(2018, 1, 1), null, null));
        ResumeExperience expV2 = entityManager.persistAndFlush(new ResumeExperience(
                resumeV2, "Acme Corp", "Engineer", LocalDate.of(2018, 1, 1), null, null));

        assertThat(expV1.getResume().getId()).isEqualTo(resumeV1.getId());
        assertThat(expV2.getResume().getId()).isEqualTo(resumeV2.getId());
    }

    @Test
    void nullableFieldsBehaveAccordingToTheSchema() {
        User user = persistUser("experience-test-7@example.com");
        Resume resume = persistResume(user, 1);

        // end_date null = current position; description null = not provided.
        ResumeExperience current = new ResumeExperience(resume, "Acme Corp", "Engineer",
                LocalDate.of(2023, 3, 1), null, null);
        UUID id = entityManager.persistAndFlush(current).getId();

        entityManager.clear();
        ResumeExperience reloaded = entityManager.find(ResumeExperience.class, id);

        assertThat(reloaded.getEndDate()).isNull();
        assertThat(reloaded.getDescription()).isNull();
        // NOT NULL fields are still required/present:
        assertThat(reloaded.getCompanyName()).isEqualTo("Acme Corp");
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2023, 3, 1));
    }
}
