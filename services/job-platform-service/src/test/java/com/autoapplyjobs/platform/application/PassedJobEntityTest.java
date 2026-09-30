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
 * Verifies the {@link PassedJob} JPA mapping - relationships to
 * {@link User} and {@link Job}, the {@code (user_id, job_id)} uniqueness
 * rule, {@code ON DELETE CASCADE} on both FKs, and field mutability -
 * against the real PostgreSQL schema (no H2/SQLite).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PassedJobEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    private Job newJob(String dedupFingerprint) {
        Company company = entityManager.persistAndFlush(
                new Company("Acme Corp " + dedupFingerprint, "acme corp " + dedupFingerprint, null, null));
        Instant now = Instant.now();
        return entityManager.persistAndFlush(new Job(company, "Backend Engineer",
                "backend engineer", "Build and maintain backend services.", null, null,
                null, null, null, null, null, null, null, null, dedupFingerprint, null,
                JobStatus.ACTIVE, now, now, null));
    }

    private int nativeDelete(String table, UUID id) {
        return entityManager.getEntityManager()
                .createNativeQuery("DELETE FROM " + table + " WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    // --- basic mapping ---

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        User user = persistUser("passed-1@example.com");
        Job job = newJob("pfp-1");
        Instant passedAt = Instant.now();
        UUID id = entityManager.persistAndFlush(new PassedJob(user, job, passedAt, "Too far to commute")).getId();

        entityManager.clear();
        PassedJob reloaded = entityManager.find(PassedJob.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getPassedAt()).isCloseTo(passedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getReason()).isEqualTo("Too far to commute");
    }

    @Test
    void relationshipsResolveLazilyToTheCorrectRows() {
        User user = persistUser("passed-2@example.com");
        Job job = newJob("pfp-2");
        UUID id = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null)).getId();

        entityManager.clear();
        PassedJob reloaded = entityManager.find(PassedJob.class, id);

        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getUser().getEmail()).isEqualTo("passed-2@example.com");
        assertThat(reloaded.getJob().getId()).isEqualTo(job.getId());
        assertThat(reloaded.getJob().getTitle()).isEqualTo("Backend Engineer");
    }

    @Test
    void reasonIsNullable() {
        User user = persistUser("passed-3@example.com");
        Job job = newJob("pfp-3");
        UUID id = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null)).getId();

        entityManager.clear();
        PassedJob reloaded = entityManager.find(PassedJob.class, id);

        assertThat(reloaded.getReason()).isNull();
        assertThat(reloaded.getPassedAt()).isNotNull();
    }

    @Test
    void passedAtIsRequiredByTheDatabase() {
        User user = persistUser("passed-4@example.com");
        Job job = newJob("pfp-4");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO passed_job (user_id, job_id) VALUES (:userId, :jobId)")
                .setParameter("userId", user.getId())
                .setParameter("jobId", job.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    // --- FK violations ---

    @Test
    void nonexistentUserFailsDueToTheDatabaseFk() {
        User nonExistentUser = entityManager.getEntityManager().getReference(User.class, UUID.randomUUID());
        Job job = newJob("pfp-5");

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                new PassedJob(nonExistentUser, job, Instant.now(), null)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentJobFailsDueToTheDatabaseFk() {
        User user = persistUser("passed-6@example.com");
        Job nonExistentJob = entityManager.getEntityManager().getReference(Job.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                new PassedJob(user, nonExistentJob, Instant.now(), null)))
                .isInstanceOf(PersistenceException.class);
    }

    // --- uniqueness ---

    @Test
    void duplicateUserAndJobFails() {
        User user = persistUser("passed-7@example.com");
        Job job = newJob("pfp-7");
        entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null));

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                new PassedJob(user, job, Instant.now(), "second pass attempt")))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theSameUserCanPassDifferentJobsAndDifferentUsersCanPassTheSameJob() {
        User firstUser = persistUser("passed-8a@example.com");
        User secondUser = persistUser("passed-8b@example.com");
        Job firstJob = newJob("pfp-8a");
        Job secondJob = newJob("pfp-8b");

        entityManager.persistAndFlush(new PassedJob(firstUser, firstJob, Instant.now(), null));
        entityManager.persistAndFlush(new PassedJob(firstUser, secondJob, Instant.now(), null));
        entityManager.persistAndFlush(new PassedJob(secondUser, firstJob, Instant.now(), null));

        Long count = entityManager.getEntityManager()
                .createQuery("select count(p) from PassedJob p where p.user = :u1 or p.user = :u2", Long.class)
                .setParameter("u1", firstUser)
                .setParameter("u2", secondUser)
                .getSingleResult();
        assertThat(count).isEqualTo(3L);
    }

    @Test
    void aJobCanBeBothSavedAndPassedBySameUserBecauseNoCrossTableRuleExists() {
        // No schema constraint links saved_job and passed_job; any
        // exclusivity would be application logic, which is not in scope.
        User user = persistUser("passed-9@example.com");
        Job job = newJob("pfp-9");

        entityManager.persistAndFlush(new SavedJob(user, job, Instant.now(), null));
        PassedJob passed = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null));

        assertThat(passed.getId()).isNotNull();
    }

    // --- mutability ---

    @Test
    void userIdCannotBeChangedThroughJpa() {
        User originalUser = persistUser("passed-10a@example.com");
        User otherUser = persistUser("passed-10b@example.com");
        Job job = newJob("pfp-10");
        PassedJob passedJob = entityManager.persistAndFlush(new PassedJob(originalUser, job, Instant.now(), null));
        UUID id = passedJob.getId();

        ReflectionTestUtils.setField(passedJob, "user", otherUser);
        entityManager.persistAndFlush(passedJob);
        entityManager.clear();

        assertThat(entityManager.find(PassedJob.class, id).getUser().getId()).isEqualTo(originalUser.getId());
    }

    @Test
    void jobIdCannotBeChangedThroughJpa() {
        User user = persistUser("passed-11@example.com");
        Job originalJob = newJob("pfp-11a");
        Job otherJob = newJob("pfp-11b");
        PassedJob passedJob = entityManager.persistAndFlush(new PassedJob(user, originalJob, Instant.now(), null));
        UUID id = passedJob.getId();

        ReflectionTestUtils.setField(passedJob, "job", otherJob);
        entityManager.persistAndFlush(passedJob);
        entityManager.clear();

        assertThat(entityManager.find(PassedJob.class, id).getJob().getId()).isEqualTo(originalJob.getId());
    }

    @Test
    void passedAtCannotBeChangedThroughJpa() {
        User user = persistUser("passed-12@example.com");
        Job job = newJob("pfp-12");
        Instant originalPassedAt = Instant.now().minusSeconds(3600);
        PassedJob passedJob = entityManager.persistAndFlush(new PassedJob(user, job, originalPassedAt, null));
        UUID id = passedJob.getId();

        ReflectionTestUtils.setField(passedJob, "passedAt", Instant.now());
        entityManager.persistAndFlush(passedJob);
        entityManager.clear();

        assertThat(entityManager.find(PassedJob.class, id).getPassedAt())
                .isCloseTo(originalPassedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void reasonCanBeChangedThroughJpa() {
        User user = persistUser("passed-13@example.com");
        Job job = newJob("pfp-13");
        PassedJob passedJob = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), "original"));
        UUID id = passedJob.getId();

        ReflectionTestUtils.setField(passedJob, "reason", "updated");
        entityManager.persistAndFlush(passedJob);
        entityManager.clear();

        assertThat(entityManager.find(PassedJob.class, id).getReason()).isEqualTo("updated");
    }

    // --- ON DELETE CASCADE (both FKs) ---

    @Test
    void deletingTheUserCascadesToPassedJob() {
        User user = persistUser("passed-14@example.com");
        Job job = newJob("pfp-14");
        UUID id = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null)).getId();
        entityManager.clear();

        nativeDelete("app_user", user.getId());

        assertThat(entityManager.find(PassedJob.class, id)).isNull();
    }

    @Test
    void deletingTheJobCascadesToPassedJob() {
        User user = persistUser("passed-15@example.com");
        Job job = newJob("pfp-15");
        UUID id = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null)).getId();
        entityManager.clear();

        nativeDelete("job", job.getId());

        assertThat(entityManager.find(PassedJob.class, id)).isNull();
    }

    @Test
    void passedJobMayBeHardDeleted() {
        User user = persistUser("passed-16@example.com");
        Job job = newJob("pfp-16");
        PassedJob passedJob = entityManager.persistAndFlush(new PassedJob(user, job, Instant.now(), null));
        UUID id = passedJob.getId();

        entityManager.remove(passedJob);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(PassedJob.class, id)).isNull();
    }
}
