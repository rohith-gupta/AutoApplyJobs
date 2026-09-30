package com.autoapplyjobs.platform.application;

import com.autoapplyjobs.platform.company.Company;
import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.job.JobStatus;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies the {@link ApplicationStatusHistory} JPA mapping against the
 * real PostgreSQL schema (no H2/SQLite): column mapping, the
 * {@link ApplicationStatus} enum and its CHECK constraint, the FK to
 * {@link Application} and its {@code ON DELETE CASCADE}, the DB-generated
 * {@code created_at}, and append-only (fully non-updatable) behavior.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApplicationStatusHistoryEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private Application newApplication(String key) {
        User user = entityManager.persistAndFlush(new User("hist-" + key + "@example.com", "hash", "Test User"));
        Company company = entityManager.persistAndFlush(
                new Company("Acme Corp " + key, "acme corp " + key, null, null));
        Instant now = Instant.now();
        Job job = entityManager.persistAndFlush(new Job(company, "Backend Engineer",
                "backend engineer", "Build and maintain backend services.", null, null,
                null, null, null, null, null, null, null, null, "hfp-" + key, null,
                JobStatus.ACTIVE, now, now, null));
        Resume resume = entityManager.persistAndFlush(new Resume(user, 1,
                "s3://resumes/" + key + ".pdf", "resume.pdf", false, now));
        return entityManager.persistAndFlush(new Application(user, job, resume, null, null, null, now,
                ApplicationStatus.APPLIED, null));
    }

    // --- basic mapping ---

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Application application = newApplication("1");
        Instant changedAt = Instant.now().minusSeconds(120);
        UUID id = entityManager.persistAndFlush(new ApplicationStatusHistory(application,
                ApplicationStatus.SCREENING, changedAt, "Recruiter phone screen booked")).getId();

        entityManager.clear();
        ApplicationStatusHistory reloaded = entityManager.find(ApplicationStatusHistory.class, id);

        assertThat(reloaded.getApplication().getId()).isEqualTo(application.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.SCREENING);
        assertThat(reloaded.getChangedAt()).isCloseTo(changedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getNote()).isEqualTo("Recruiter phone screen booked");
    }

    @Test
    void databaseGeneratedFieldsPopulateAndNoteIsNullable() {
        Application application = newApplication("2");

        ApplicationStatusHistory persisted = entityManager.persistAndFlush(
                new ApplicationStatusHistory(application, ApplicationStatus.APPLIED, Instant.now(), null));

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        entityManager.clear();
        assertThat(entityManager.find(ApplicationStatusHistory.class, persisted.getId()).getNote()).isNull();
    }

    @Test
    void applicationRelationshipIsLazy() {
        Application application = newApplication("3");
        UUID id = entityManager.persistAndFlush(new ApplicationStatusHistory(application,
                ApplicationStatus.APPLIED, Instant.now(), null)).getId();

        entityManager.clear();
        ApplicationStatusHistory reloaded = entityManager.find(ApplicationStatusHistory.class, id);

        assertThat(Hibernate.isInitialized(reloaded.getApplication())).isFalse();
    }

    @Test
    void oneApplicationCanAccumulateMultipleHistoryRowsIncludingRepeatedStatuses() {
        // No uniqueness on (application_id, status) or (application_id,
        // changed_at) in V1 - it is a free-form append-only trail.
        Application application = newApplication("4");
        Instant t0 = Instant.now().minusSeconds(300);
        entityManager.persistAndFlush(new ApplicationStatusHistory(application, ApplicationStatus.APPLIED, t0, null));
        entityManager.persistAndFlush(new ApplicationStatusHistory(application, ApplicationStatus.SCREENING,
                t0.plusSeconds(60), null));
        entityManager.persistAndFlush(new ApplicationStatusHistory(application, ApplicationStatus.SCREENING,
                t0.plusSeconds(60), "duplicate entry"));
        entityManager.clear();

        List<ApplicationStatus> trail = entityManager.getEntityManager()
                .createQuery("""
                        select h.status from ApplicationStatusHistory h
                        where h.application.id = :applicationId order by h.changedAt, h.createdAt
                        """, ApplicationStatus.class)
                .setParameter("applicationId", application.getId())
                .getResultList();
        assertThat(trail).containsExactly(ApplicationStatus.APPLIED, ApplicationStatus.SCREENING,
                ApplicationStatus.SCREENING);
    }

    // --- NOT NULL / FK / CHECK ---

    @Test
    void changedAtIsRequiredByTheDatabase() {
        Application application = newApplication("5");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO application_status_history (application_id, status)
                        VALUES (:applicationId, 'APPLIED')
                        """)
                .setParameter("applicationId", application.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentApplicationFailsDueToTheDatabaseFk() {
        Application missing = entityManager.getEntityManager().getReference(Application.class, UUID.randomUUID());

        assertThatThrownBy(() -> entityManager.persistAndFlush(
                new ApplicationStatusHistory(missing, ApplicationStatus.APPLIED, Instant.now(), null)))
                .isInstanceOf(PersistenceException.class);
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void everyApplicationStatusIsAcceptedByTheCheckConstraint(ApplicationStatus status) {
        Application application = newApplication("6-" + status.name());
        UUID id = entityManager.persistAndFlush(
                new ApplicationStatusHistory(application, status, Instant.now(), null)).getId();

        entityManager.clear();

        assertThat(entityManager.find(ApplicationStatusHistory.class, id).getStatus()).isEqualTo(status);
    }

    @Test
    void anInvalidStatusIsRejectedByTheCheckConstraint() {
        Application application = newApplication("7");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO application_status_history (application_id, status, changed_at)
                        VALUES (:applicationId, 'NOT_A_REAL_STATUS', now())
                        """)
                .setParameter("applicationId", application.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    // --- ON DELETE CASCADE. Applications are never hard-deleted by the
    // application layer, so the realistic cascade path is deletion of the
    // owning app_user (app_user -> application -> history). ---

    @Test
    void deletingTheOwningUserCascadesThroughApplicationToHistory() {
        Application application = newApplication("8");
        UUID userId = application.getUser().getId();
        UUID id = entityManager.persistAndFlush(new ApplicationStatusHistory(application,
                ApplicationStatus.APPLIED, Instant.now(), null)).getId();
        entityManager.clear();

        entityManager.getEntityManager()
                .createNativeQuery("DELETE FROM app_user WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();

        assertThat(entityManager.find(ApplicationStatusHistory.class, id)).isNull();
    }

    // --- append-only: every column is non-updatable through JPA ---

    @Test
    void noFieldCanBeChangedThroughJpa() {
        Application original = newApplication("9a");
        Application other = newApplication("9b");
        Instant changedAt = Instant.now().minusSeconds(600);
        ApplicationStatusHistory history = entityManager.persistAndFlush(new ApplicationStatusHistory(original,
                ApplicationStatus.INTERVIEW, changedAt, "original note"));
        UUID id = history.getId();
        Instant createdAt = history.getCreatedAt();

        ReflectionTestUtils.setField(history, "application", other);
        ReflectionTestUtils.setField(history, "status", ApplicationStatus.OFFER);
        ReflectionTestUtils.setField(history, "changedAt", Instant.now());
        ReflectionTestUtils.setField(history, "note", "rewritten note");
        ReflectionTestUtils.setField(history, "createdAt", Instant.now().plusSeconds(3600));
        entityManager.persistAndFlush(history);
        entityManager.clear();

        ApplicationStatusHistory reloaded = entityManager.find(ApplicationStatusHistory.class, id);
        assertThat(reloaded.getApplication().getId()).isEqualTo(original.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(reloaded.getChangedAt()).isCloseTo(changedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getNote()).isEqualTo("original note");
        assertThat(reloaded.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void recordingANewStatusDoesNotAlterEarlierHistoryRows() {
        Application application = newApplication("10");
        Instant t0 = Instant.now().minusSeconds(300);
        UUID firstId = entityManager.persistAndFlush(new ApplicationStatusHistory(application,
                ApplicationStatus.APPLIED, t0, "first")).getId();

        ReflectionTestUtils.setField(application, "currentStatus", ApplicationStatus.REJECTED);
        entityManager.persistAndFlush(application);
        entityManager.persistAndFlush(new ApplicationStatusHistory(application, ApplicationStatus.REJECTED,
                t0.plusSeconds(120), "second"));
        entityManager.clear();

        ApplicationStatusHistory first = entityManager.find(ApplicationStatusHistory.class, firstId);
        assertThat(first.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(first.getNote()).isEqualTo("first");
        assertThat(entityManager.find(Application.class, application.getId()).getCurrentStatus())
                .isEqualTo(ApplicationStatus.REJECTED);
    }
}
