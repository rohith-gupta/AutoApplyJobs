package com.autoapplyjobs.platform.application;

import com.autoapplyjobs.platform.company.Company;
import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.job.JobSource;
import com.autoapplyjobs.platform.job.JobStatus;
import com.autoapplyjobs.platform.matching.JobMatch;
import com.autoapplyjobs.platform.resume.Resume;
import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.PersistenceException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the {@link Application} JPA mapping against the real PostgreSQL
 * schema (no H2/SQLite): every column mapping, nullability,
 * {@code NUMERIC(5,2)} precision, {@link ApplicationStatus} enum mapping
 * and its CHECK constraint, the absence of {@code (user_id, job_id)}
 * uniqueness (reapplication), every FK's {@code ON DELETE} behavior,
 * lazy relationships, DB-generated columns, and per-field mutability.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApplicationEntityTest {

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

    private Resume persistResume(User user, int versionNumber) {
        return entityManager.persistAndFlush(new Resume(user, versionNumber,
                "s3://resumes/" + user.getId() + "/v" + versionNumber + ".pdf", "resume.pdf", false, Instant.now()));
    }

    private JobMatch persistJobMatch(Job job, Resume resume, String algorithmVersion, String overallScore) {
        return entityManager.persistAndFlush(new JobMatch(job, resume, algorithmVersion,
                new BigDecimal(overallScore), null, null, null, null, null, null, null, Instant.now()));
    }

    private JobSource persistJobSource(Job job, String nativeId) {
        Instant now = Instant.now();
        return entityManager.persistAndFlush(new JobSource(job, "greenhouse", nativeId,
                "https://boards.example.com/jobs/" + nativeId, now, now));
    }

    /** Minimal application - only the NOT NULL columns set. */
    private Application minimalApplication(User user, Job job, Resume resume) {
        return new Application(user, job, resume, null, null, null, Instant.now(), ApplicationStatus.APPLIED, null);
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
        User user = persistUser("app-1@example.com");
        Job job = newJob("afp-1");
        Resume resume = persistResume(user, 1);
        JobMatch jobMatch = persistJobMatch(job, resume, "MATCH_V1", "81.25");
        JobSource source = persistJobSource(job, "gh-1");
        Instant appliedAt = Instant.now().minusSeconds(60);

        UUID id = entityManager.persistAndFlush(new Application(user, job, resume, jobMatch, source,
                new BigDecimal("81.25"), appliedAt, ApplicationStatus.SCREENING,
                "https://boards.example.com/applications/123")).getId();

        entityManager.clear();
        Application reloaded = entityManager.find(Application.class, id);

        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getJob().getId()).isEqualTo(job.getId());
        assertThat(reloaded.getResume().getId()).isEqualTo(resume.getId());
        assertThat(reloaded.getJobMatch().getId()).isEqualTo(jobMatch.getId());
        assertThat(reloaded.getAppliedViaJobSource().getId()).isEqualTo(source.getId());
        assertThat(reloaded.getJobMatchScoreAtApplication()).isEqualByComparingTo("81.25");
        assertThat(reloaded.getJobMatchScoreAtApplication().scale()).isEqualTo(2);
        assertThat(reloaded.getAppliedAt()).isCloseTo(appliedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getCurrentStatus()).isEqualTo(ApplicationStatus.SCREENING);
        assertThat(reloaded.getExternalApplicationUrl()).isEqualTo("https://boards.example.com/applications/123");
        assertThat(reloaded.getArchivedAt()).isNull();
    }

    @Test
    void databaseGeneratedFieldsPopulateOnInsert() {
        User user = persistUser("app-2@example.com");
        Job job = newJob("afp-2");
        Resume resume = persistResume(user, 1);

        Application persisted = entityManager.persistAndFlush(minimalApplication(user, job, resume));

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    @Test
    void optionalColumnsAreNullable() {
        User user = persistUser("app-3@example.com");
        Job job = newJob("afp-3");
        Resume resume = persistResume(user, 1);
        UUID id = entityManager.persistAndFlush(minimalApplication(user, job, resume)).getId();

        entityManager.clear();
        Application reloaded = entityManager.find(Application.class, id);

        assertThat(reloaded.getJobMatch()).isNull();
        assertThat(reloaded.getAppliedViaJobSource()).isNull();
        assertThat(reloaded.getJobMatchScoreAtApplication()).isNull();
        assertThat(reloaded.getExternalApplicationUrl()).isNull();
        assertThat(reloaded.getArchivedAt()).isNull();
    }

    @Test
    void relationshipsAreLazy() {
        User user = persistUser("app-4@example.com");
        Job job = newJob("afp-4");
        Resume resume = persistResume(user, 1);
        JobMatch jobMatch = persistJobMatch(job, resume, "MATCH_V1", "50.00");
        JobSource source = persistJobSource(job, "gh-4");
        UUID id = entityManager.persistAndFlush(new Application(user, job, resume, jobMatch, source,
                null, Instant.now(), ApplicationStatus.APPLIED, null)).getId();

        entityManager.clear();
        Application reloaded = entityManager.find(Application.class, id);

        assertThat(Hibernate.isInitialized(reloaded.getUser())).isFalse();
        assertThat(Hibernate.isInitialized(reloaded.getJob())).isFalse();
        assertThat(Hibernate.isInitialized(reloaded.getResume())).isFalse();
        assertThat(Hibernate.isInitialized(reloaded.getJobMatch())).isFalse();
        assertThat(Hibernate.isInitialized(reloaded.getAppliedViaJobSource())).isFalse();
    }

    // --- NOT NULL columns and the DB default on current_status ---

    @Test
    void currentStatusDefaultsToAppliedAtTheDatabaseLevel() {
        User user = persistUser("app-5@example.com");
        Job job = newJob("afp-5");
        Resume resume = persistResume(user, 1);

        entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO application (user_id, job_id, resume_id, applied_at)
                        VALUES (:userId, :jobId, :resumeId, now())
                        """)
                .setParameter("userId", user.getId())
                .setParameter("jobId", job.getId())
                .setParameter("resumeId", resume.getId())
                .executeUpdate();

        Application reloaded = entityManager.getEntityManager()
                .createQuery("select a from Application a where a.user = :user", Application.class)
                .setParameter("user", user)
                .getSingleResult();
        assertThat(reloaded.getCurrentStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void appliedAtIsRequiredByTheDatabase() {
        User user = persistUser("app-6@example.com");
        Job job = newJob("afp-6");
        Resume resume = persistResume(user, 1);

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO application (user_id, job_id, resume_id)
                        VALUES (:userId, :jobId, :resumeId)
                        """)
                .setParameter("userId", user.getId())
                .setParameter("jobId", job.getId())
                .setParameter("resumeId", resume.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void resumeIsRequiredByTheDatabase() {
        User user = persistUser("app-7@example.com");
        Job job = newJob("afp-7");

        assertThatThrownBy(() -> entityManager.persistAndFlush(minimalApplication(user, job, null)))
                .isInstanceOf(PersistenceException.class);
    }

    // --- enum mapping / CHECK constraint ---

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void everyApplicationStatusRoundTripsAsItsName(ApplicationStatus status) {
        User user = persistUser("app-8-" + status.name().toLowerCase() + "@example.com");
        Job job = newJob("afp-8-" + status.name());
        Resume resume = persistResume(user, 1);
        UUID id = entityManager.persistAndFlush(new Application(user, job, resume, null, null, null,
                Instant.now(), status, null)).getId();

        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getCurrentStatus()).isEqualTo(status);
        Object raw = entityManager.getEntityManager()
                .createNativeQuery("SELECT current_status FROM application WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(raw).isEqualTo(status.name());
    }

    @Test
    void anInvalidStatusIsRejectedByTheCheckConstraint() {
        User user = persistUser("app-9@example.com");
        Job job = newJob("afp-9");
        Resume resume = persistResume(user, 1);

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO application (user_id, job_id, resume_id, applied_at, current_status)
                        VALUES (:userId, :jobId, :resumeId, now(), 'NOT_A_REAL_STATUS')
                        """)
                .setParameter("userId", user.getId())
                .setParameter("jobId", job.getId())
                .setParameter("resumeId", resume.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    // --- NUMERIC(5,2) precision ---

    @Test
    void aScoreExceedingNumericFivePrecisionTwoIsRejectedByPostgres() {
        User user = persistUser("app-10@example.com");
        Job job = newJob("afp-10");
        Resume resume = persistResume(user, 1);

        assertThatThrownBy(() -> entityManager.persistAndFlush(new Application(user, job, resume, null, null,
                new BigDecimal("1000.00"), Instant.now(), ApplicationStatus.APPLIED, null)))
                .isInstanceOf(PersistenceException.class);
    }

    // --- reapplication (no uniqueness on user_id, job_id) ---

    @Test
    void theSameUserCanApplyToTheSameJobMoreThanOnce() {
        User user = persistUser("app-11@example.com");
        Job job = newJob("afp-11");
        Resume firstResume = persistResume(user, 1);
        Resume secondResume = persistResume(user, 2);

        Application first = entityManager.persistAndFlush(minimalApplication(user, job, firstResume));
        Application second = entityManager.persistAndFlush(minimalApplication(user, job, secondResume));
        Application third = entityManager.persistAndFlush(minimalApplication(user, job, secondResume));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(second.getId()).isNotEqualTo(third.getId());
    }

    // --- FK violations ---

    @Test
    void nonexistentUserFailsDueToTheDatabaseFk() {
        User owner = persistUser("app-12@example.com");
        Job job = newJob("afp-12");
        Resume resume = persistResume(owner, 1);
        User missing = entityManager.getEntityManager().getReference(User.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(minimalApplication(missing, job, resume)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentJobFailsDueToTheDatabaseFk() {
        User user = persistUser("app-13@example.com");
        Resume resume = persistResume(user, 1);
        Job missing = entityManager.getEntityManager().getReference(Job.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(minimalApplication(user, missing, resume)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentResumeFailsDueToTheDatabaseFk() {
        User user = persistUser("app-14@example.com");
        Job job = newJob("afp-14");
        Resume missing = entityManager.getEntityManager().getReference(Resume.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(minimalApplication(user, job, missing)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentJobMatchFailsDueToTheDatabaseFk() {
        User user = persistUser("app-15@example.com");
        Job job = newJob("afp-15");
        Resume resume = persistResume(user, 1);
        JobMatch missing = entityManager.getEntityManager().getReference(JobMatch.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(new Application(user, job, resume, missing, null,
                null, Instant.now(), ApplicationStatus.APPLIED, null)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentJobSourceFailsDueToTheDatabaseFk() {
        User user = persistUser("app-16@example.com");
        Job job = newJob("afp-16");
        Resume resume = persistResume(user, 1);
        JobSource missing = entityManager.getEntityManager().getReference(JobSource.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(new Application(user, job, resume, null, missing,
                null, Instant.now(), ApplicationStatus.APPLIED, null)))
                .isInstanceOf(PersistenceException.class);
    }

    // --- ON DELETE behavior ---

    @Test
    void deletingTheUserCascadesToTheirApplications() {
        User user = persistUser("app-17@example.com");
        Job job = newJob("afp-17");
        Resume resume = persistResume(user, 1);
        UUID id = entityManager.persistAndFlush(minimalApplication(user, job, resume)).getId();
        entityManager.clear();

        // resume.user_id also cascades from app_user; application.resume_id
        // is RESTRICT - PostgreSQL still completes the delete because the
        // application row is removed by the same cascading statement.
        nativeDelete("app_user", user.getId());

        assertThat(entityManager.find(Application.class, id)).isNull();
    }

    @Test
    void deletingTheJobIsRestrictedWhileAnApplicationReferencesIt() {
        User user = persistUser("app-18@example.com");
        Job job = newJob("afp-18");
        Resume resume = persistResume(user, 1);
        entityManager.persistAndFlush(minimalApplication(user, job, resume));
        entityManager.clear();

        assertThatThrownBy(() -> nativeDelete("job", job.getId()))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingTheResumeVersionIsRestrictedWhileAnApplicationReferencesIt() {
        User user = persistUser("app-19@example.com");
        Job job = newJob("afp-19");
        Resume resume = persistResume(user, 1);
        entityManager.persistAndFlush(minimalApplication(user, job, resume));
        entityManager.clear();

        assertThatThrownBy(() -> nativeDelete("resume", resume.getId()))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingTheJobMatchSetsJobMatchIdToNullAndLeavesTheFrozenScoreUnchanged() {
        User user = persistUser("app-20@example.com");
        Job job = newJob("afp-20");
        Resume resume = persistResume(user, 1);
        JobMatch jobMatch = persistJobMatch(job, resume, "MATCH_V1", "72.40");
        UUID id = entityManager.persistAndFlush(new Application(user, job, resume, jobMatch, null,
                new BigDecimal("72.40"), Instant.now(), ApplicationStatus.APPLIED, null)).getId();
        entityManager.clear();

        nativeDelete("job_match", jobMatch.getId());
        Application reloaded = entityManager.find(Application.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getJobMatch()).isNull();
        assertThat(reloaded.getJobMatchScoreAtApplication()).isEqualByComparingTo("72.40");
    }

    @Test
    void recomputingTheLiveJobMatchDoesNotChangeTheFrozenScore() {
        User user = persistUser("app-21@example.com");
        Job job = newJob("afp-21");
        Resume resume = persistResume(user, 1);
        JobMatch jobMatch = persistJobMatch(job, resume, "MATCH_V1", "60.00");
        UUID id = entityManager.persistAndFlush(new Application(user, job, resume, jobMatch, null,
                new BigDecimal("60.00"), Instant.now(), ApplicationStatus.APPLIED, null)).getId();

        ReflectionTestUtils.setField(jobMatch, "overallScore", new BigDecimal("90.00"));
        entityManager.persistAndFlush(jobMatch);
        entityManager.clear();

        Application reloaded = entityManager.find(Application.class, id);
        assertThat(reloaded.getJobMatch().getOverallScore()).isEqualByComparingTo("90.00");
        assertThat(reloaded.getJobMatchScoreAtApplication()).isEqualByComparingTo("60.00");
    }

    @Test
    void deletingTheJobSourceSetsAppliedViaJobSourceIdToNull() {
        User user = persistUser("app-22@example.com");
        Job job = newJob("afp-22");
        Resume resume = persistResume(user, 1);
        JobSource source = persistJobSource(job, "gh-22");
        UUID id = entityManager.persistAndFlush(new Application(user, job, resume, null, source,
                null, Instant.now(), ApplicationStatus.APPLIED, null)).getId();
        entityManager.clear();

        nativeDelete("job_source", source.getId());
        Application reloaded = entityManager.find(Application.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getAppliedViaJobSource()).isNull();
    }

    // --- mutability: application-time facts are fixed. Mutating the
    // in-memory field is just Java - these prove Hibernate excludes the
    // column from its UPDATE, so the persisted value is unaffected. ---

    @Test
    void userCannotBeChangedThroughJpa() {
        User original = persistUser("app-23a@example.com");
        User other = persistUser("app-23b@example.com");
        Job job = newJob("afp-23");
        Resume resume = persistResume(original, 1);
        Application application = entityManager.persistAndFlush(minimalApplication(original, job, resume));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "user", other);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getUser().getId()).isEqualTo(original.getId());
    }

    @Test
    void jobCannotBeChangedThroughJpa() {
        User user = persistUser("app-24@example.com");
        Job original = newJob("afp-24a");
        Job other = newJob("afp-24b");
        Resume resume = persistResume(user, 1);
        Application application = entityManager.persistAndFlush(minimalApplication(user, original, resume));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "job", other);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getJob().getId()).isEqualTo(original.getId());
    }

    @Test
    void resumeVersionCannotBeChangedThroughJpa() {
        User user = persistUser("app-25@example.com");
        Job job = newJob("afp-25");
        Resume original = persistResume(user, 1);
        Resume newer = persistResume(user, 2);
        Application application = entityManager.persistAndFlush(minimalApplication(user, job, original));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "resume", newer);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getResume().getId()).isEqualTo(original.getId());
    }

    @Test
    void jobMatchCannotBeChangedThroughJpa() {
        User user = persistUser("app-26@example.com");
        Job job = newJob("afp-26");
        Resume resume = persistResume(user, 1);
        JobMatch original = persistJobMatch(job, resume, "MATCH_V1", "50.00");
        JobMatch other = persistJobMatch(job, resume, "MATCH_V2", "55.00");
        Application application = entityManager.persistAndFlush(new Application(user, job, resume, original, null,
                new BigDecimal("50.00"), Instant.now(), ApplicationStatus.APPLIED, null));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "jobMatch", other);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getJobMatch().getId()).isEqualTo(original.getId());
    }

    @Test
    void appliedViaJobSourceCannotBeChangedThroughJpa() {
        User user = persistUser("app-27@example.com");
        Job job = newJob("afp-27");
        Resume resume = persistResume(user, 1);
        JobSource original = persistJobSource(job, "gh-27a");
        JobSource other = persistJobSource(job, "gh-27b");
        Application application = entityManager.persistAndFlush(new Application(user, job, resume, null, original,
                null, Instant.now(), ApplicationStatus.APPLIED, null));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "appliedViaJobSource", other);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getAppliedViaJobSource().getId())
                .isEqualTo(original.getId());
    }

    @Test
    void frozenScoreCannotBeChangedThroughJpa() {
        User user = persistUser("app-28@example.com");
        Job job = newJob("afp-28");
        Resume resume = persistResume(user, 1);
        Application application = entityManager.persistAndFlush(new Application(user, job, resume, null, null,
                new BigDecimal("64.00"), Instant.now(), ApplicationStatus.APPLIED, null));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "jobMatchScoreAtApplication", new BigDecimal("99.00"));
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getJobMatchScoreAtApplication())
                .isEqualByComparingTo("64.00");
    }

    @Test
    void appliedAtCannotBeChangedThroughJpa() {
        User user = persistUser("app-29@example.com");
        Job job = newJob("afp-29");
        Resume resume = persistResume(user, 1);
        Instant originalAppliedAt = Instant.now().minusSeconds(86_400);
        Application application = entityManager.persistAndFlush(new Application(user, job, resume, null, null,
                null, originalAppliedAt, ApplicationStatus.APPLIED, null));
        UUID id = application.getId();

        ReflectionTestUtils.setField(application, "appliedAt", Instant.now());
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getAppliedAt())
                .isCloseTo(originalAppliedAt, within(1, ChronoUnit.MICROS));
    }

    // --- mutability: current, user-maintained state is updatable ---

    @Test
    void currentStatusExternalUrlAndArchivedAtCanBeChangedThroughJpa() {
        User user = persistUser("app-30@example.com");
        Job job = newJob("afp-30");
        Resume resume = persistResume(user, 1);
        Application application = entityManager.persistAndFlush(new Application(user, job, resume, null, null,
                null, Instant.now(), ApplicationStatus.APPLIED, "https://old.example.com"));
        UUID id = application.getId();
        Instant archivedAt = Instant.now();

        ReflectionTestUtils.setField(application, "currentStatus", ApplicationStatus.INTERVIEW);
        ReflectionTestUtils.setField(application, "externalApplicationUrl", "https://new.example.com");
        ReflectionTestUtils.setField(application, "archivedAt", archivedAt);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        Application reloaded = entityManager.find(Application.class, id);
        assertThat(reloaded.getCurrentStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(reloaded.getExternalApplicationUrl()).isEqualTo("https://new.example.com");
        assertThat(reloaded.getArchivedAt()).isCloseTo(archivedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void createdAtCannotBeChangedThroughJpa() {
        User user = persistUser("app-31@example.com");
        Job job = newJob("afp-31");
        Resume resume = persistResume(user, 1);
        Application application = entityManager.persistAndFlush(minimalApplication(user, job, resume));
        UUID id = application.getId();
        Instant originalCreatedAt = application.getCreatedAt();

        ReflectionTestUtils.setField(application, "createdAt", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(application, "currentStatus", ApplicationStatus.REJECTED);
        entityManager.persistAndFlush(application);
        entityManager.clear();

        assertThat(entityManager.find(Application.class, id).getCreatedAt())
                .isCloseTo(originalCreatedAt, within(1, ChronoUnit.MICROS));
    }
}
