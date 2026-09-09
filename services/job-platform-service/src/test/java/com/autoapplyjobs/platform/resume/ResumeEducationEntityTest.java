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
 * Verifies the {@link ResumeEducation} JPA mapping - including its
 * relationship to {@link Resume} - against the real PostgreSQL schema
 * created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as the rest of this package's tests.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResumeEducationEntityTest {

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
        User user = persistUser("education-test-1@example.com");
        Resume resume = persistResume(user, 1);
        ResumeEducation education = new ResumeEducation(resume, "State University", "B.S.",
                "Computer Science", LocalDate.of(2014, 9, 1), LocalDate.of(2018, 5, 15));

        ResumeEducation persisted = entityManager.persistAndFlush(education);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        User user = persistUser("education-test-2@example.com");
        Resume resume = persistResume(user, 1);
        ResumeEducation education = new ResumeEducation(resume, "State University", "B.S.",
                "Computer Science", LocalDate.of(2014, 9, 1), LocalDate.of(2018, 5, 15));
        UUID id = entityManager.persistAndFlush(education).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        ResumeEducation reloaded = entityManager.find(ResumeEducation.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getInstitutionName()).isEqualTo("State University");
        assertThat(reloaded.getDegree()).isEqualTo("B.S.");
        assertThat(reloaded.getFieldOfStudy()).isEqualTo("Computer Science");
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2014, 9, 1));
        assertThat(reloaded.getEndDate()).isEqualTo(LocalDate.of(2018, 5, 15));
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void resumeRelationshipResolvesCorrectly() {
        User user = persistUser("education-test-3@example.com");
        Resume resume = persistResume(user, 1);
        ResumeEducation education = new ResumeEducation(resume, "State University", null,
                null, null, null);
        UUID id = entityManager.persistAndFlush(education).getId();

        entityManager.clear();
        ResumeEducation reloaded = entityManager.find(ResumeEducation.class, id);

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
        ResumeEducation orphan = new ResumeEducation(nonExistentResume, "State University",
                null, null, null, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleEducationRowsForTheSameResumeSucceed() {
        User user = persistUser("education-test-5@example.com");
        Resume resume = persistResume(user, 1);

        ResumeEducation first = entityManager.persistAndFlush(new ResumeEducation(
                resume, "State University", "B.S.", "Computer Science",
                LocalDate.of(2014, 9, 1), LocalDate.of(2018, 5, 15)));
        ResumeEducation second = entityManager.persistAndFlush(new ResumeEducation(
                resume, "Tech Institute", "M.S.", "Software Engineering",
                LocalDate.of(2018, 9, 1), LocalDate.of(2020, 5, 15)));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void educationRowsForDifferentResumeVersionsSucceedIndependently() {
        User user = persistUser("education-test-6@example.com");
        Resume resumeV1 = persistResume(user, 1);
        Resume resumeV2 = persistResume(user, 2);

        ResumeEducation eduV1 = entityManager.persistAndFlush(new ResumeEducation(
                resumeV1, "State University", "B.S.", "Computer Science", null, null));
        ResumeEducation eduV2 = entityManager.persistAndFlush(new ResumeEducation(
                resumeV2, "State University", "B.S.", "Computer Science", null, null));

        assertThat(eduV1.getResume().getId()).isEqualTo(resumeV1.getId());
        assertThat(eduV2.getResume().getId()).isEqualTo(resumeV2.getId());
    }

    @Test
    void everyNullableFieldBehavesAccordingToTheSchema() {
        User user = persistUser("education-test-7@example.com");
        Resume resume = persistResume(user, 1);

        // degree, field_of_study, start_date, end_date are all nullable;
        // only institution_name is required.
        ResumeEducation minimal = new ResumeEducation(resume, "State University",
                null, null, null, null);
        UUID id = entityManager.persistAndFlush(minimal).getId();

        entityManager.clear();
        ResumeEducation reloaded = entityManager.find(ResumeEducation.class, id);

        assertThat(reloaded.getDegree()).isNull();
        assertThat(reloaded.getFieldOfStudy()).isNull();
        assertThat(reloaded.getStartDate()).isNull();
        assertThat(reloaded.getEndDate()).isNull();
        // The NOT NULL field is still required/present:
        assertThat(reloaded.getInstitutionName()).isEqualTo("State University");
    }
}
