package com.autoapplyjobs.platform.resume;

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
 * Verifies the {@link ResumeProfile} JPA mapping - including its
 * shared-primary-key relationship to {@link Resume} - against the real
 * PostgreSQL schema created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as the rest of this package's tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResumeProfileEntityTest {

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
        User user = persistUser("profile-test-1@example.com");
        Resume resume = persistResume(user, 1);
        ResumeProfile profile = new ResumeProfile(resume, "Jane Doe", "Senior Engineer",
                new BigDecimal("8.5"), "Backend-focused engineer.");

        ResumeProfile persisted = entityManager.persistAndFlush(profile);

        assertThat(persisted.getResumeId()).isEqualTo(resume.getId());
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        User user = persistUser("profile-test-2@example.com");
        Resume resume = persistResume(user, 1);
        entityManager.persistAndFlush(new ResumeProfile(resume, "Jane Doe", "Senior Engineer",
                new BigDecimal("8.5"), "Backend-focused engineer."));
        UUID id = resume.getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        ResumeProfile reloaded = entityManager.find(ResumeProfile.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getFullName()).isEqualTo("Jane Doe");
        assertThat(reloaded.getHeadline()).isEqualTo("Senior Engineer");
        assertThat(reloaded.getYearsOfExperience()).isEqualByComparingTo("8.5");
        assertThat(reloaded.getSummary()).isEqualTo("Backend-focused engineer.");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void resumeRelationshipResolvesCorrectly() {
        User user = persistUser("profile-test-3@example.com");
        Resume resume = persistResume(user, 1);
        entityManager.persistAndFlush(new ResumeProfile(resume, null, null, null, null));
        UUID id = resume.getId();

        entityManager.clear();
        ResumeProfile reloaded = entityManager.find(ResumeProfile.class, id);

        assertThat(reloaded.getResume()).isNotNull();
        assertThat(reloaded.getResume().getId()).isEqualTo(id);
        assertThat(reloaded.getResume().getVersionNumber()).isEqualTo(1);
    }

    @Test
    void violatesForeignKeyWithoutAValidResume() {
        // A proxy for a Resume id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Resume nonExistentResume = entityManager.getEntityManager()
                .getReference(Resume.class, UUID.randomUUID());
        ResumeProfile orphan = new ResumeProfile(nonExistentResume, null, null, null, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void enforcesOneProfilePerResumeViaThePrimaryKey() {
        User user = persistUser("profile-test-5@example.com");
        Resume resume = persistResume(user, 1);
        entityManager.persistAndFlush(new ResumeProfile(resume, null, null, null, null));

        // Same resume -> same derived id -> primary key collision.
        ResumeProfile duplicate = new ResumeProfile(resume, "Different Name", null, null, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void profilesForDifferentResumeVersionsSucceedIndependently() {
        User user = persistUser("profile-test-6@example.com");
        Resume resumeV1 = persistResume(user, 1);
        Resume resumeV2 = persistResume(user, 2);

        ResumeProfile profileV1 = entityManager.persistAndFlush(
                new ResumeProfile(resumeV1, "V1 Name", null, null, null));
        ResumeProfile profileV2 = entityManager.persistAndFlush(
                new ResumeProfile(resumeV2, "V2 Name", null, null, null));

        assertThat(profileV1.getResumeId()).isEqualTo(resumeV1.getId());
        assertThat(profileV2.getResumeId()).isEqualTo(resumeV2.getId());
    }
}
