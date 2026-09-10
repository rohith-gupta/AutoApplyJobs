package com.autoapplyjobs.platform.job;

import com.autoapplyjobs.platform.company.Company;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link Job} JPA mapping - including its relationship to
 * {@link Company}, the partial-unique {@code (company_id, requisition_id)}
 * identity signal, the deliberately-non-unique candidate signals, and the
 * {@link JobStatus} enum mapping - against the real PostgreSQL schema
 * created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private Company persistCompany(String name, String normalizedName) {
        return entityManager.persistAndFlush(new Company(name, normalizedName, null, null));
    }

    /** A fully-populated Job, useful where every field's round-trip matters. */
    private Job fullJob(Company company, String requisitionId, String dedupFingerprint,
                         String applyUrlHash) {
        Instant now = Instant.now();
        return new Job(company, "Backend Engineer", "backend engineer",
                "Build and maintain backend services.", requisitionId,
                "https://acme.com/apply/123", applyUrlHash, "FULL_TIME", "REMOTE",
                new BigDecimal("120000.00"), new BigDecimal("160000.00"), "USD",
                new BigDecimal("3.0"), new BigDecimal("8.0"), dedupFingerprint,
                now, JobStatus.ACTIVE, now, now, null);
    }

    /** A minimally-populated Job - only NOT NULL columns supplied. */
    private Job minimalJob(Company company, String dedupFingerprint) {
        Instant now = Instant.now();
        return new Job(company, "Backend Engineer", "backend engineer",
                "Build and maintain backend services.", null, null, null, null, null,
                null, null, null, null, null, dedupFingerprint, null, JobStatus.ACTIVE,
                now, now, null);
    }

    @Test
    void persistsWithAValidCompany() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = fullJob(company, "REQ-1", "fp-1", "hash-1");

        Job persisted = entityManager.persistAndFlush(job);

        assertThat(persisted.getId()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Instant postedAt = Instant.now();
        Instant firstSeenAt = Instant.now();
        Instant lastSeenAt = Instant.now();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        Job job = new Job(company, "Backend Engineer", "backend engineer",
                "Build and maintain backend services.", "REQ-2",
                "https://acme.com/apply/456", "hash-2", "FULL_TIME", "REMOTE",
                new BigDecimal("120000.00"), new BigDecimal("160000.00"), "USD",
                new BigDecimal("3.0"), new BigDecimal("8.0"), "fp-2",
                postedAt, JobStatus.ACTIVE, firstSeenAt, lastSeenAt, expiresAt);
        UUID id = entityManager.persistAndFlush(job).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        Job reloaded = entityManager.find(Job.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getTitle()).isEqualTo("Backend Engineer");
        assertThat(reloaded.getNormalizedTitle()).isEqualTo("backend engineer");
        assertThat(reloaded.getDescription()).isEqualTo("Build and maintain backend services.");
        assertThat(reloaded.getRequisitionId()).isEqualTo("REQ-2");
        assertThat(reloaded.getCanonicalApplyUrl()).isEqualTo("https://acme.com/apply/456");
        assertThat(reloaded.getCanonicalApplyUrlHash()).isEqualTo("hash-2");
        assertThat(reloaded.getEmploymentType()).isEqualTo("FULL_TIME");
        assertThat(reloaded.getRemoteType()).isEqualTo("REMOTE");
        assertThat(reloaded.getSalaryMin()).isEqualByComparingTo("120000.00");
        assertThat(reloaded.getSalaryMax()).isEqualByComparingTo("160000.00");
        assertThat(reloaded.getSalaryCurrency()).isEqualTo("USD");
        assertThat(reloaded.getExperienceMinYears()).isEqualByComparingTo("3.0");
        assertThat(reloaded.getExperienceMaxYears()).isEqualByComparingTo("8.0");
        assertThat(reloaded.getDedupFingerprint()).isEqualTo("fp-2");
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void companyRelationshipResolvesCorrectly() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = fullJob(company, "REQ-3", "fp-3", "hash-3");
        UUID id = entityManager.persistAndFlush(job).getId();

        entityManager.clear();
        Job reloaded = entityManager.find(Job.class, id);

        assertThat(reloaded.getCompany()).isNotNull();
        assertThat(reloaded.getCompany().getId()).isEqualTo(company.getId());
        assertThat(reloaded.getCompany().getNormalizedName()).isEqualTo("acme corp");
    }

    @Test
    void violatesForeignKeyWithoutAValidCompany() {
        // A proxy for a Company id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Company nonExistentCompany = entityManager.getEntityManager()
                .getReference(Company.class, UUID.randomUUID());
        Job orphan = fullJob(nonExistentCompany, "REQ-4", "fp-4", "hash-4");

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void duplicateCompanyAndRequisitionIdWithNonNullRequisitionIdFails() {
        Company company = persistCompany("Acme Corp", "acme corp");
        entityManager.persistAndFlush(fullJob(company, "REQ-5", "fp-5a", "hash-5a"));

        Job duplicate = fullJob(company, "REQ-5", "fp-5b", "hash-5b");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleJobsWithNullRequisitionIdForTheSameCompanySucceed() {
        Company company = persistCompany("Acme Corp", "acme corp");

        Job first = entityManager.persistAndFlush(fullJob(company, null, "fp-6a", "hash-6a"));
        Job second = entityManager.persistAndFlush(fullJob(company, null, "fp-6b", "hash-6b"));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void duplicateDedupFingerprintSucceeds() {
        // Proves dedup_fingerprint is a candidate signal, not a unique
        // constraint - two genuinely different jobs may share it (e.g.
        // same company, same title, same location, different openings).
        Company company = persistCompany("Acme Corp", "acme corp");

        Job first = entityManager.persistAndFlush(fullJob(company, "REQ-7A", "fp-same", "hash-7a"));
        Job second = entityManager.persistAndFlush(fullJob(company, "REQ-7B", "fp-same", "hash-7b"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getDedupFingerprint()).isEqualTo(second.getDedupFingerprint());
    }

    @Test
    void duplicateCanonicalApplyUrlHashSucceeds() {
        // Proves canonical_apply_url_hash is a candidate signal, not a
        // unique constraint.
        Company company = persistCompany("Acme Corp", "acme corp");

        Job first = entityManager.persistAndFlush(fullJob(company, "REQ-8A", "fp-8a", "hash-same"));
        Job second = entityManager.persistAndFlush(fullJob(company, "REQ-8B", "fp-8b", "hash-same"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getCanonicalApplyUrlHash()).isEqualTo(second.getCanonicalApplyUrlHash());
    }

    @Test
    void nullableFieldsBehaveAccordingToTheSchema() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = minimalJob(company, "fp-9");
        UUID id = entityManager.persistAndFlush(job).getId();

        entityManager.clear();
        Job reloaded = entityManager.find(Job.class, id);

        assertThat(reloaded.getRequisitionId()).isNull();
        assertThat(reloaded.getCanonicalApplyUrl()).isNull();
        assertThat(reloaded.getCanonicalApplyUrlHash()).isNull();
        assertThat(reloaded.getEmploymentType()).isNull();
        assertThat(reloaded.getRemoteType()).isNull();
        assertThat(reloaded.getSalaryMin()).isNull();
        assertThat(reloaded.getSalaryMax()).isNull();
        assertThat(reloaded.getSalaryCurrency()).isNull();
        assertThat(reloaded.getExperienceMinYears()).isNull();
        assertThat(reloaded.getExperienceMaxYears()).isNull();
        assertThat(reloaded.getPostedAt()).isNull();
        assertThat(reloaded.getExpiresAt()).isNull();
        // Required fields are still present:
        assertThat(reloaded.getTitle()).isEqualTo("Backend Engineer");
        assertThat(reloaded.getDedupFingerprint()).isEqualTo("fp-9");
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.ACTIVE);
    }

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = fullJob(company, "REQ-10", "fp-10", "hash-10");

        Job persisted = entityManager.persistAndFlush(job);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    @Test
    void validStatusValuesPersistSuccessfully() {
        Company company = persistCompany("Acme Corp", "acme corp");

        Job active = entityManager.persistAndFlush(fullJob(company, "REQ-11A", "fp-11a", "hash-11a"));
        Instant now = Instant.now();
        Job expired = entityManager.persistAndFlush(new Job(company, "Backend Engineer",
                "backend engineer", "desc", "REQ-11B", null, null, null, null, null, null,
                null, null, null, "fp-11b", null, JobStatus.EXPIRED, now, now, null));
        Job closed = entityManager.persistAndFlush(new Job(company, "Backend Engineer",
                "backend engineer", "desc", "REQ-11C", null, null, null, null, null, null,
                null, null, null, "fp-11c", null, JobStatus.CLOSED, now, now, null));

        assertThat(active.getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(expired.getStatus()).isEqualTo(JobStatus.EXPIRED);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    @Test
    void anInvalidRawStatusIsRejectedByPostgresWithoutWeakeningTheJavaEnumMapping() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Instant now = Instant.now();

        // Bypasses JPA/the Job entity entirely via a native insert, to
        // prove PostgreSQL's chk_job_status CHECK constraint itself
        // rejects an invalid raw status string - independent of the fact
        // that JobStatus's Java enum type already makes constructing an
        // invalid Job impossible at compile time. Nothing about the Java
        // mapping is loosened to make this test possible.
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO job (company_id, title, normalized_title, description,
                                          dedup_fingerprint, status, first_seen_at, last_seen_at)
                        VALUES (:companyId, 'Bad Status Job', 'bad status job', 'desc',
                                'fp-invalid-status', 'NOT_A_REAL_STATUS', :now, :now)
                        """)
                .setParameter("companyId", company.getId())
                .setParameter("now", now)
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    // --- Mutability re-clarification: company and requisitionId are
    // canonical/correctable, not fixed identity. Job has no setters by
    // design, so these tests use ReflectionTestUtils to mutate the private
    // fields directly - proving Hibernate's dirty-checking honors the JPA
    // "updatable" mapping itself, independent of whether any service-level
    // enrichment/reassignment API exists yet (it doesn't). ---

    @Test
    void aJobCreatedWithNullRequisitionIdCanLaterReceiveOne() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = entityManager.persistAndFlush(fullJob(company, null, "fp-12", "hash-12"));
        UUID id = job.getId();
        assertThat(job.getRequisitionId()).isNull();

        ReflectionTestUtils.setField(job, "requisitionId", "REQ-12-LATER");
        entityManager.persistAndFlush(job);
        entityManager.clear();

        Job reloaded = entityManager.find(Job.class, id);
        assertThat(reloaded.getRequisitionId()).isEqualTo("REQ-12-LATER");
    }

    @Test
    void aJobCanLaterBeReassignedToAnotherExistingCompany() {
        Company originalCompany = persistCompany("Acme Corp", "acme corp");
        Company correctCompany = persistCompany("Acme Corp Holdings", "acme corp holdings");
        Job job = entityManager.persistAndFlush(fullJob(originalCompany, "REQ-13", "fp-13", "hash-13"));
        UUID id = job.getId();

        ReflectionTestUtils.setField(job, "company", correctCompany);
        entityManager.persistAndFlush(job);
        entityManager.clear();

        Job reloaded = entityManager.find(Job.class, id);
        assertThat(reloaded.getCompany().getId()).isEqualTo(correctCompany.getId());
        assertThat(reloaded.getCompany().getId()).isNotEqualTo(originalCompany.getId());
    }

    @Test
    void partialUniqueConstraintStillRejectsAConflictingCompanyAndRequisitionIdCombination() {
        Company company = persistCompany("Acme Corp", "acme corp");
        entityManager.persistAndFlush(fullJob(company, "REQ-14-TAKEN", "fp-14a", "hash-14a"));
        Job second = entityManager.persistAndFlush(fullJob(company, "REQ-14-OTHER", "fp-14b", "hash-14b"));

        // Mutate the second job's requisitionId to collide with the
        // first's - proves the partial unique index is enforced on
        // UPDATE too, not just INSERT.
        ReflectionTestUtils.setField(second, "requisitionId", "REQ-14-TAKEN");

        assertThatThrownBy(() -> entityManager.persistAndFlush(second))
                .isInstanceOf(PersistenceException.class);
    }
}
