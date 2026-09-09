package com.autoapplyjobs.platform.resume;

import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the {@link Resume} JPA mapping - including its relationship to
 * {@link User} and the unique/default-resume invariants - against the real
 * PostgreSQL schema created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as the {@code user} package's tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResumeEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    @Test
    void persistsForAnExistingUser() {
        User user = persistUser("resume-test-1@example.com");
        Resume resume = new Resume(user, 1, "file://r1", null, false, Instant.now());

        Resume persisted = entityManager.persistAndFlush(resume);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void reloadsCorrectly() {
        User user = persistUser("resume-test-2@example.com");
        Instant uploadedAt = Instant.now();
        Resume resume = new Resume(user, 1, "file://r2", "resume.pdf", true, uploadedAt);
        UUID id = entityManager.persistAndFlush(resume).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        Resume reloaded = entityManager.find(Resume.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getVersionNumber()).isEqualTo(1);
        assertThat(reloaded.getFileUrl()).isEqualTo("file://r2");
        assertThat(reloaded.getOriginalFilename()).isEqualTo("resume.pdf");
        assertThat(reloaded.isDefault()).isTrue();
        // PostgreSQL TIMESTAMPTZ has microsecond precision and the JDBC
        // driver rounds (not truncates) the nanosecond value, so compare
        // with a tolerance rather than exact equality - 1 microsecond
        // comfortably covers that rounding without masking a real defect.
        assertThat(reloaded.getUploadedAt()).isCloseTo(uploadedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void userRelationshipResolvesCorrectly() {
        User user = persistUser("resume-test-3@example.com");
        Resume resume = new Resume(user, 1, "file://r3", null, false, Instant.now());
        UUID id = entityManager.persistAndFlush(resume).getId();

        entityManager.clear();
        Resume reloaded = entityManager.find(Resume.class, id);

        assertThat(reloaded.getUser()).isNotNull();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getUser().getEmail()).isEqualTo("resume-test-3@example.com");
    }

    @Test
    void duplicateUserAndVersionNumberFails() {
        User user = persistUser("resume-test-4@example.com");
        entityManager.persistAndFlush(new Resume(user, 1, "file://r4a", null, false, Instant.now()));

        Resume duplicate = new Resume(user, 1, "file://r4b", null, false, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void twoDefaultResumesForTheSameUserFails() {
        User user = persistUser("resume-test-5@example.com");
        entityManager.persistAndFlush(new Resume(user, 1, "file://r5a", null, true, Instant.now()));

        Resume secondDefault = new Resume(user, 2, "file://r5b", null, true, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(secondDefault))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void differentUsersMayEachHaveTheirOwnDefaultResume() {
        User userA = persistUser("resume-test-6a@example.com");
        User userB = persistUser("resume-test-6b@example.com");

        Resume defaultA = entityManager.persistAndFlush(new Resume(userA, 1, "file://r6a", null, true, Instant.now()));
        Resume defaultB = entityManager.persistAndFlush(new Resume(userB, 1, "file://r6b", null, true, Instant.now()));

        assertThat(defaultA.isDefault()).isTrue();
        assertThat(defaultB.isDefault()).isTrue();
    }

    @Test
    void nonDefaultMultipleResumeVersionsForOneUserSucceed() {
        User user = persistUser("resume-test-7@example.com");

        Resume v1 = entityManager.persistAndFlush(new Resume(user, 1, "file://r7v1", null, false, Instant.now()));
        Resume v2 = entityManager.persistAndFlush(new Resume(user, 2, "file://r7v2", null, false, Instant.now()));
        Resume v3 = entityManager.persistAndFlush(new Resume(user, 3, "file://r7v3", null, false, Instant.now()));

        assertThat(v1.getId()).isNotNull();
        assertThat(v2.getId()).isNotNull();
        assertThat(v3.getId()).isNotNull();
    }

    @Test
    void anExistingNonDefaultResumeCanBecomeDefault() {
        User user = persistUser("resume-test-8@example.com");
        Resume resume = entityManager.persistAndFlush(
                new Resume(user, 1, "file://r8", null, false, Instant.now()));
        UUID id = resume.getId();

        resume.markAsDefault();
        entityManager.persistAndFlush(resume);
        entityManager.clear();

        Resume reloaded = entityManager.find(Resume.class, id);
        assertThat(reloaded.isDefault()).isTrue();
    }

    @Test
    void anExistingDefaultResumeCanBecomeNonDefault() {
        User user = persistUser("resume-test-9@example.com");
        Resume resume = entityManager.persistAndFlush(
                new Resume(user, 1, "file://r9", null, true, Instant.now()));
        UUID id = resume.getId();

        resume.removeAsDefault();
        entityManager.persistAndFlush(resume);
        entityManager.clear();

        Resume reloaded = entityManager.find(Resume.class, id);
        assertThat(reloaded.isDefault()).isFalse();
    }

    @Test
    void contentAndVersionFieldsRemainUnchangedWhenTheDefaultFlagChanges() {
        User user = persistUser("resume-test-10@example.com");
        Instant uploadedAt = Instant.now();
        Resume resume = entityManager.persistAndFlush(
                new Resume(user, 1, "file://r10", "resume.pdf", false, uploadedAt));
        UUID id = resume.getId();
        Instant createdAt = resume.getCreatedAt();

        resume.markAsDefault();
        entityManager.persistAndFlush(resume);
        entityManager.clear();

        Resume reloaded = entityManager.find(Resume.class, id);
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getVersionNumber()).isEqualTo(1);
        assertThat(reloaded.getFileUrl()).isEqualTo("file://r10");
        assertThat(reloaded.getOriginalFilename()).isEqualTo("resume.pdf");
        assertThat(reloaded.getUploadedAt()).isCloseTo(uploadedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        // The one field that was allowed to change:
        assertThat(reloaded.isDefault()).isTrue();
    }

    @Test
    void postgresStillRejectsTwoDefaultResumesForTheSameUserViaMarkAsDefault() {
        User user = persistUser("resume-test-11@example.com");
        entityManager.persistAndFlush(new Resume(user, 1, "file://r11a", null, true, Instant.now()));
        Resume second = entityManager.persistAndFlush(
                new Resume(user, 2, "file://r11b", null, false, Instant.now()));

        second.markAsDefault();

        assertThatThrownBy(() -> entityManager.persistAndFlush(second))
                .isInstanceOf(PersistenceException.class);
    }
}
