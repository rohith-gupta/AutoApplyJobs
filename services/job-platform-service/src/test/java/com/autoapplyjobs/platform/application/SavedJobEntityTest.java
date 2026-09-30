package com.autoapplyjobs.platform.application;

import com.autoapplyjobs.platform.company.Company;
import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.job.JobStatus;
import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the {@link SavedJob} JPA mapping - including its relationships
 * to {@link User} and {@link Job} and the {@code (user_id, job_id)}
 * identity/uniqueness rule - against the real PostgreSQL schema created by
 * {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SavedJobEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    private Company persistCompany(String name, String normalizedName) {
        return entityManager.persistAndFlush(new Company(name, normalizedName, null, null));
    }

    private Job persistJob(Company company, String dedupFingerprint) {
        Instant now = Instant.now();
        return entityManager.persistAndFlush(new Job(company, "Backend Engineer",
                "backend engineer", "Build and maintain backend services.", null, null,
                null, null, null, null, null, null, null, null, dedupFingerprint, null,
                JobStatus.ACTIVE, now, now, null));
    }

    private Job newJob(String dedupFingerprint) {
        Company company = persistCompany("Acme Corp " + dedupFingerprint, "acme corp " + dedupFingerprint);
        return persistJob(company, dedupFingerprint);
    }

    // --- 1-2: basic mapping ---

    @Test
    void persistsForAnExistingUserAndJob() {
        User user = persistUser("saved-1@example.com");
        Job job = newJob("fp-1");
        SavedJob savedJob = new SavedJob(user, job, Instant.now(), null);

        SavedJob persisted = entityManager.persistAndFlush(savedJob);

        assertThat(persisted.getId()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        User user = persistUser("saved-2@example.com");
        Job job = newJob("fp-2");
        Instant savedAt = Instant.now();
        SavedJob savedJob = new SavedJob(user, job, savedAt, "Looks promising, follow up Monday");
        UUID id = entityManager.persistAndFlush(savedJob).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        SavedJob reloaded = entityManager.find(SavedJob.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getSavedAt()).isCloseTo(savedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getNote()).isEqualTo("Looks promising, follow up Monday");
    }

    // --- 3-4: relationships ---

    @Test
    void userRelationshipResolvesCorrectly() {
        User user = persistUser("saved-3@example.com");
        Job job = newJob("fp-3");
        SavedJob savedJob = new SavedJob(user, job, Instant.now(), null);
        UUID id = entityManager.persistAndFlush(savedJob).getId();

        entityManager.clear();
        SavedJob reloaded = entityManager.find(SavedJob.class, id);

        assertThat(reloaded.getUser()).isNotNull();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getUser().getEmail()).isEqualTo("saved-3@example.com");
    }

    @Test
    void jobRelationshipResolvesCorrectly() {
        User user = persistUser("saved-4@example.com");
        Job job = newJob("fp-4");
        SavedJob savedJob = new SavedJob(user, job, Instant.now(), null);
        UUID id = entityManager.persistAndFlush(savedJob).getId();

        entityManager.clear();
        SavedJob reloaded = entityManager.find(SavedJob.class, id);

        assertThat(reloaded.getJob()).isNotNull();
        assertThat(reloaded.getJob().getId()).isEqualTo(job.getId());
        assertThat(reloaded.getJob().getTitle()).isEqualTo("Backend Engineer");
    }

    // --- 5-6: FK violations ---

    @Test
    void nonexistentUserFailsDueToTheDatabaseFk() {
        // A proxy for a User id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        User nonExistentUser = entityManager.getEntityManager()
                .getReference(User.class, UUID.randomUUID());
        Job job = newJob("fp-5");
        SavedJob orphan = new SavedJob(nonExistentUser, job, Instant.now(), null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentJobFailsDueToTheDatabaseFk() {
        User user = persistUser("saved-6@example.com");
        // A proxy for a Job id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        Job nonExistentJob = entityManager.getEntityManager()
                .getReference(Job.class, UUID.randomUUID());
        SavedJob orphan = new SavedJob(user, nonExistentJob, Instant.now(), null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 7-9: identity/uniqueness ---

    @Test
    void duplicateUserAndJobFails() {
        User user = persistUser("saved-7@example.com");
        Job job = newJob("fp-7");
        entityManager.persistAndFlush(new SavedJob(user, job, Instant.now(), null));

        SavedJob duplicate = new SavedJob(user, job, Instant.now(), "second save attempt");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theSameUserCanSaveDifferentJobs() {
        User user = persistUser("saved-8@example.com");
        Job firstJob = newJob("fp-8a");
        Job secondJob = newJob("fp-8b");

        SavedJob first = entityManager.persistAndFlush(new SavedJob(user, firstJob, Instant.now(), null));
        SavedJob second = entityManager.persistAndFlush(new SavedJob(user, secondJob, Instant.now(), null));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void theSameJobCanBeSavedByDifferentUsers() {
        Job job = newJob("fp-9");
        User firstUser = persistUser("saved-9a@example.com");
        User secondUser = persistUser("saved-9b@example.com");

        SavedJob first = entityManager.persistAndFlush(new SavedJob(firstUser, job, Instant.now(), null));
        SavedJob second = entityManager.persistAndFlush(new SavedJob(secondUser, job, Instant.now(), null));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getJob().getId()).isEqualTo(second.getJob().getId());
    }

    // --- 10-11: identity-field immutability. Mutating the in-memory
    // field is just Java - these prove Hibernate excludes the columns
    // from its UPDATE statement, so the persisted values are unaffected. ---

    @Test
    void userIdCannotBeChangedThroughJpa() {
        User originalUser = persistUser("saved-10a@example.com");
        User otherUser = persistUser("saved-10b@example.com");
        Job job = newJob("fp-10");
        SavedJob savedJob = entityManager.persistAndFlush(new SavedJob(originalUser, job, Instant.now(), null));
        UUID id = savedJob.getId();

        ReflectionTestUtils.setField(savedJob, "user", otherUser);
        entityManager.persistAndFlush(savedJob);
        entityManager.clear();

        assertThat(entityManager.find(SavedJob.class, id).getUser().getId())
                .isEqualTo(originalUser.getId());
    }

    @Test
    void jobIdCannotBeChangedThroughJpa() {
        User user = persistUser("saved-11@example.com");
        Job originalJob = newJob("fp-11a");
        Job otherJob = newJob("fp-11b");
        SavedJob savedJob = entityManager.persistAndFlush(new SavedJob(user, originalJob, Instant.now(), null));
        UUID id = savedJob.getId();

        ReflectionTestUtils.setField(savedJob, "job", otherJob);
        entityManager.persistAndFlush(savedJob);
        entityManager.clear();

        assertThat(entityManager.find(SavedJob.class, id).getJob().getId())
                .isEqualTo(originalJob.getId());
    }

    // --- savedAt/note mutability (resolved after the initial mapping
    // step). SavedJob has no setters by design, so - consistent with how
    // every other entity's mutability was verified - these use
    // ReflectionTestUtils to mutate the private fields directly, proving
    // Hibernate's dirty-checking honors the JPA "updatable" mapping,
    // independent of whether any service-level edit-note API exists yet
    // (it doesn't). ---

    @Test
    void savedAtCannotBeChangedThroughJpa() {
        User user = persistUser("saved-14@example.com");
        Job job = newJob("fp-14");
        Instant originalSavedAt = Instant.now().minusSeconds(3600);
        SavedJob savedJob = entityManager.persistAndFlush(new SavedJob(user, job, originalSavedAt, null));
        UUID id = savedJob.getId();

        ReflectionTestUtils.setField(savedJob, "savedAt", Instant.now());
        entityManager.persistAndFlush(savedJob);
        entityManager.clear();

        assertThat(entityManager.find(SavedJob.class, id).getSavedAt())
                .isCloseTo(originalSavedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void noteCanBeChangedThroughJpa() {
        User user = persistUser("saved-15@example.com");
        Job job = newJob("fp-15");
        SavedJob savedJob = entityManager.persistAndFlush(new SavedJob(user, job, Instant.now(), "original note"));
        UUID id = savedJob.getId();

        ReflectionTestUtils.setField(savedJob, "note", "updated note");
        entityManager.persistAndFlush(savedJob);
        entityManager.clear();

        assertThat(entityManager.find(SavedJob.class, id).getNote()).isEqualTo("updated note");
    }

    // --- 12: database-generated fields, and nullable fields behaving
    // according to the schema ---

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        User user = persistUser("saved-12@example.com");
        Job job = newJob("fp-12");
        SavedJob savedJob = new SavedJob(user, job, Instant.now(), null);

        SavedJob persisted = entityManager.persistAndFlush(savedJob);

        assertThat(persisted.getId()).isNotNull();
    }

    @Test
    void noteIsNullableAndSavedAtIsRequired() {
        // note is the only nullable column besides id; user_id, job_id,
        // and saved_at are all NOT NULL in V1. saved_job has no
        // database-generated column at all (no created_at), unlike most
        // other tables in this schema.
        User user = persistUser("saved-13@example.com");
        Job job = newJob("fp-13");
        SavedJob minimal = new SavedJob(user, job, Instant.now(), null);
        UUID id = entityManager.persistAndFlush(minimal).getId();

        entityManager.clear();
        SavedJob reloaded = entityManager.find(SavedJob.class, id);

        assertThat(reloaded.getNote()).isNull();
        // Required fields are still present:
        assertThat(reloaded.getSavedAt()).isNotNull();
    }
}
