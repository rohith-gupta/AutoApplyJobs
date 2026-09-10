package com.autoapplyjobs.platform.job;

import com.autoapplyjobs.platform.company.Company;
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
 * Verifies the {@link RawJobProcessing} JPA mapping - including its
 * cross-service-boundary {@code raw_job_posting_id} scalar field, its
 * {@code job_source_id} relationship, the required-at-creation
 * {@code normalizer_version} rule added by
 * {@code V3__require_raw_job_processing_normalizer_version.sql}, and the
 * {@code (raw_job_posting_id, normalizer_version)} uniqueness rule -
 * against the real PostgreSQL schema created by
 * {@code db/migration/V1__initial_schema.sql} as amended by {@code V3}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 *
 * <p>{@code job-platform-service} has no {@code RawJobPosting} JPA entity
 * (it is owned exclusively by {@code job-ingestion-service} - see
 * {@link RawJobProcessing}'s class Javadoc), so every {@code raw_job_posting}
 * row this test needs is created via narrowly-scoped native SQL, strictly
 * matching the {@code raw_job_posting} table as defined in
 * {@code V1__initial_schema.sql}. This native SQL is test-fixture-only;
 * production code never writes to {@code raw_job_posting}.
 *
 * <p>Every {@link RawJobProcessing} constructed below supplies a non-null
 * {@code normalizerVersion} - per the resolved design, a processing row is
 * only ever created after the application has already selected which
 * normalizer will process a given raw posting, never as a generic
 * placeholder.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RawJobProcessingEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    /** Test-only fixture: inserts a raw_job_posting row via native SQL and returns its id. */
    private UUID insertRawJobPostingFixture(String sourceUrl, String contentHash) {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager().createNativeQuery("""
                        INSERT INTO raw_job_posting (id, source_name, source_url, raw_payload, content_hash, fetched_at)
                        VALUES (:id, :sourceName, :sourceUrl, CAST(:payload AS JSONB), :contentHash, :fetchedAt)
                        """)
                .setParameter("id", id)
                .setParameter("sourceName", "linkedin")
                .setParameter("sourceUrl", sourceUrl)
                .setParameter("payload", "{}")
                .setParameter("contentHash", contentHash)
                .setParameter("fetchedAt", Instant.now())
                .executeUpdate();
        return id;
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

    private JobSource persistJobSource(String dedupFingerprint) {
        Company company = persistCompany("Acme Corp " + dedupFingerprint, "acme corp " + dedupFingerprint);
        Job job = persistJob(company, dedupFingerprint);
        return entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-" + dedupFingerprint,
                "https://linkedin.com/jobs/" + dedupFingerprint, Instant.now(), Instant.now()));
    }

    // --- 1-2: basic mapping ---

    @Test
    void persistsForAnExistingRawJobPostingFixtureWithANonNullNormalizerVersion() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/1", "hash-1");
        RawJobProcessing processing = new RawJobProcessing(postingId, ProcessingStatus.PENDING,
                null, "normalizer-v1", null, null, 0);

        RawJobProcessing persisted = entityManager.persistAndFlush(processing);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getNormalizerVersion()).isEqualTo("normalizer-v1");
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/2", "hash-2");
        JobSource jobSource = persistJobSource("fp-2");
        Instant processedAt = Instant.now();
        RawJobProcessing processing = new RawJobProcessing(postingId, ProcessingStatus.PROCESSED,
                jobSource, "normalizer-v1", processedAt, null, 1);
        UUID id = entityManager.persistAndFlush(processing).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        RawJobProcessing reloaded = entityManager.find(RawJobProcessing.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getRawJobPostingId()).isEqualTo(postingId);
        assertThat(reloaded.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(reloaded.getJobSource().getId()).isEqualTo(jobSource.getId());
        assertThat(reloaded.getNormalizerVersion()).isEqualTo("normalizer-v1");
        assertThat(reloaded.getProcessedAt()).isCloseTo(processedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getErrorMessage()).isNull();
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    // --- normalizer_version is now required at creation (V3) ---

    @Test
    void attemptingToInsertANullNormalizerVersionFails() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/2b", "hash-2b");
        RawJobProcessing withoutNormalizerVersion = new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, null, null, null, 0);

        assertThatThrownBy(() -> entityManager.persistAndFlush(withoutNormalizerVersion))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aNativeInsertWithANullNormalizerVersionIsRejectedByPostgres() {
        // Bypasses the RawJobProcessing entity entirely via a native
        // insert, to prove PostgreSQL's own NOT NULL constraint on
        // normalizer_version (added by V3) rejects it directly -
        // independent of the JPA-level nullable = false mapping.
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/2c", "hash-2c");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO raw_job_processing (raw_job_posting_id, processing_status, normalizer_version)
                        VALUES (:postingId, 'PENDING', NULL)
                        """)
                .setParameter("postingId", postingId)
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    // --- 3: FK to raw_job_posting ---

    @Test
    void nonexistentRawJobPostingIdFailsDueToTheDatabaseFk() {
        RawJobProcessing orphan = new RawJobProcessing(UUID.randomUUID(), ProcessingStatus.PENDING,
                null, "normalizer-v1", null, null, 0);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nonexistentJobSourceIdFailsDueToTheDatabaseFk() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/3b", "hash-3b");
        // A proxy for a JobSource id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        JobSource nonExistentJobSource = entityManager.getEntityManager()
                .getReference(JobSource.class, UUID.randomUUID());
        RawJobProcessing orphan = new RawJobProcessing(postingId, ProcessingStatus.PROCESSED,
                nonExistentJobSource, "normalizer-v1", Instant.now(), null, 1);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 4-6: uniqueness ---

    @Test
    void duplicateRawJobPostingIdAndNormalizerVersionFails() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/4", "hash-4");
        entityManager.persistAndFlush(new RawJobProcessing(postingId, ProcessingStatus.PENDING,
                null, "normalizer-v1", null, null, 0));

        RawJobProcessing duplicate = new RawJobProcessing(postingId, ProcessingStatus.PENDING,
                null, "normalizer-v1", null, null, 0);

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void sameRawPostingWithDifferentNormalizerVersionsSucceeds() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/5", "hash-5");

        RawJobProcessing first = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        RawJobProcessing second = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v2", null, null, 0));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void differentRawPostingsWithTheSameNormalizerVersionSucceed() {
        UUID firstPostingId = insertRawJobPostingFixture("https://linkedin.com/jobs/6a", "hash-6a");
        UUID secondPostingId = insertRawJobPostingFixture("https://linkedin.com/jobs/6b", "hash-6b");

        RawJobProcessing first = entityManager.persistAndFlush(new RawJobProcessing(firstPostingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        RawJobProcessing second = entityManager.persistAndFlush(new RawJobProcessing(secondPostingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getNormalizerVersion()).isEqualTo(second.getNormalizerVersion());
    }

    // --- 7-10: mutable retry/result state. RawJobProcessing has no
    // setters by design, so - consistent with how every other entity's
    // mutability was verified - these use ReflectionTestUtils to mutate
    // the private fields directly, proving Hibernate's dirty-checking
    // honors the JPA "updatable" mapping, independent of whether any
    // service-level retry/processing workflow exists yet (it doesn't). ---

    @Test
    void processingStatusCanTransitionBetweenValidStates() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/7", "hash-7");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        UUID id = processing.getId();

        ReflectionTestUtils.setField(processing, "processingStatus", ProcessingStatus.PROCESSING);
        entityManager.persistAndFlush(processing);
        entityManager.clear();
        assertThat(entityManager.find(RawJobProcessing.class, id).getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);

        RawJobProcessing reloaded = entityManager.find(RawJobProcessing.class, id);
        ReflectionTestUtils.setField(reloaded, "processingStatus", ProcessingStatus.PROCESSED);
        entityManager.persistAndFlush(reloaded);
        entityManager.clear();

        assertThat(entityManager.find(RawJobProcessing.class, id).getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSED);
    }

    @Test
    void attemptCountCanIncreaseOnRetry() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/8", "hash-8");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.FAILED, null, "normalizer-v1", null, "timeout", 1));
        UUID id = processing.getId();

        ReflectionTestUtils.setField(processing, "attemptCount", 2);
        entityManager.persistAndFlush(processing);
        entityManager.clear();

        assertThat(entityManager.find(RawJobProcessing.class, id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    void errorMessageCanBePopulatedThenClearedOnASuccessfulRetry() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/9", "hash-9");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        UUID id = processing.getId();
        assertThat(processing.getErrorMessage()).isNull();

        // A failed attempt populates error_message.
        ReflectionTestUtils.setField(processing, "processingStatus", ProcessingStatus.FAILED);
        ReflectionTestUtils.setField(processing, "errorMessage", "connection reset");
        ReflectionTestUtils.setField(processing, "attemptCount", 1);
        entityManager.persistAndFlush(processing);
        entityManager.clear();
        assertThat(entityManager.find(RawJobProcessing.class, id).getErrorMessage())
                .isEqualTo("connection reset");

        // A later successful retry on the same row (same
        // raw_job_posting_id + normalizer_version) clears it -
        // error_message is nullable per V1, so NULL is a valid,
        // meaningful "no error" state.
        RawJobProcessing reloaded = entityManager.find(RawJobProcessing.class, id);
        ReflectionTestUtils.setField(reloaded, "processingStatus", ProcessingStatus.PROCESSED);
        ReflectionTestUtils.setField(reloaded, "errorMessage", null);
        ReflectionTestUtils.setField(reloaded, "attemptCount", 2);
        entityManager.persistAndFlush(reloaded);
        entityManager.clear();

        RawJobProcessing afterRetry = entityManager.find(RawJobProcessing.class, id);
        assertThat(afterRetry.getErrorMessage()).isNull();
        assertThat(afterRetry.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSED);
        // Still the same row - no per-attempt history, same identity pair.
        assertThat(afterRetry.getId()).isEqualTo(id);
        assertThat(afterRetry.getRawJobPostingId()).isEqualTo(postingId);
        assertThat(afterRetry.getNormalizerVersion()).isEqualTo("normalizer-v1");
    }

    @Test
    void processedAtCanChangeAccordingToProcessingOutcome() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/10", "hash-10");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        UUID id = processing.getId();
        assertThat(processing.getProcessedAt()).isNull();

        Instant processedAt = Instant.now();
        ReflectionTestUtils.setField(processing, "processingStatus", ProcessingStatus.PROCESSED);
        ReflectionTestUtils.setField(processing, "processedAt", processedAt);
        entityManager.persistAndFlush(processing);
        entityManager.clear();

        RawJobProcessing reloaded = entityManager.find(RawJobProcessing.class, id);
        assertThat(reloaded.getProcessedAt()).isCloseTo(processedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void jobSourceCanBeSetOnceNormalizationResolvesToAJobSource() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/10b", "hash-10b");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        UUID id = processing.getId();
        assertThat(processing.getJobSource()).isNull();

        JobSource jobSource = persistJobSource("fp-10b");
        ReflectionTestUtils.setField(processing, "processingStatus", ProcessingStatus.PROCESSED);
        ReflectionTestUtils.setField(processing, "jobSource", jobSource);
        entityManager.persistAndFlush(processing);
        entityManager.clear();

        RawJobProcessing reloaded = entityManager.find(RawJobProcessing.class, id);
        assertThat(reloaded.getJobSource()).isNotNull();
        assertThat(reloaded.getJobSource().getId()).isEqualTo(jobSource.getId());
    }

    @Test
    void aFailedAttemptCanLaterRetryThroughProcessingToProcessed() {
        // The full FAILED -> PROCESSING -> PROCESSED retry sequence on one
        // row, as currently designed: no per-attempt history, the same
        // (raw_job_posting_id, normalizer_version) row is updated in
        // place at every step.
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/10c", "hash-10c");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.FAILED, null, "normalizer-v1", null, "first attempt timed out", 1));
        UUID id = processing.getId();

        ReflectionTestUtils.setField(processing, "processingStatus", ProcessingStatus.PROCESSING);
        ReflectionTestUtils.setField(processing, "attemptCount", 2);
        entityManager.persistAndFlush(processing);
        entityManager.clear();
        RawJobProcessing duringRetry = entityManager.find(RawJobProcessing.class, id);
        assertThat(duringRetry.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(duringRetry.getErrorMessage()).isEqualTo("first attempt timed out");

        JobSource jobSource = persistJobSource("fp-10c");
        Instant processedAt = Instant.now();
        ReflectionTestUtils.setField(duringRetry, "processingStatus", ProcessingStatus.PROCESSED);
        ReflectionTestUtils.setField(duringRetry, "errorMessage", null);
        ReflectionTestUtils.setField(duringRetry, "jobSource", jobSource);
        ReflectionTestUtils.setField(duringRetry, "processedAt", processedAt);
        entityManager.persistAndFlush(duringRetry);
        entityManager.clear();

        RawJobProcessing finished = entityManager.find(RawJobProcessing.class, id);
        assertThat(finished.getId()).isEqualTo(id);
        assertThat(finished.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(finished.getErrorMessage()).isNull();
        assertThat(finished.getAttemptCount()).isEqualTo(2);
        assertThat(finished.getJobSource().getId()).isEqualTo(jobSource.getId());
        assertThat(finished.getProcessedAt()).isCloseTo(processedAt, within(1, ChronoUnit.MICROS));
        assertThat(finished.getRawJobPostingId()).isEqualTo(postingId);
        assertThat(finished.getNormalizerVersion()).isEqualTo("normalizer-v1");
    }

    // --- 11-12: identity-pair immutability. Mutating the in-memory field
    // is just Java - these prove Hibernate excludes the columns from its
    // UPDATE statement, so the persisted values are unaffected. ---

    @Test
    void rawJobPostingIdCannotBeChangedByJpa() {
        UUID originalPostingId = insertRawJobPostingFixture("https://linkedin.com/jobs/11a", "hash-11a");
        UUID otherPostingId = insertRawJobPostingFixture("https://linkedin.com/jobs/11b", "hash-11b");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(originalPostingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        UUID id = processing.getId();

        ReflectionTestUtils.setField(processing, "rawJobPostingId", otherPostingId);
        entityManager.persistAndFlush(processing);
        entityManager.clear();

        assertThat(entityManager.find(RawJobProcessing.class, id).getRawJobPostingId())
                .isEqualTo(originalPostingId);
    }

    @Test
    void normalizerVersionCannotBeChangedByJpa() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/12", "hash-12");
        RawJobProcessing processing = entityManager.persistAndFlush(new RawJobProcessing(postingId,
                ProcessingStatus.PENDING, null, "normalizer-v1", null, null, 0));
        UUID id = processing.getId();

        ReflectionTestUtils.setField(processing, "normalizerVersion", "normalizer-v2");
        entityManager.persistAndFlush(processing);
        entityManager.clear();

        assertThat(entityManager.find(RawJobProcessing.class, id).getNormalizerVersion())
                .isEqualTo("normalizer-v1");
    }

    // --- 13: invalid status is rejected by PostgreSQL directly ---

    @Test
    void anInvalidRawProcessingStatusIsRejectedByPostgresWithoutWeakeningTheJavaEnumMapping() {
        UUID postingId = insertRawJobPostingFixture("https://linkedin.com/jobs/13", "hash-13");

        // Bypasses the RawJobProcessing entity entirely via a native
        // insert, to prove PostgreSQL's chk_raw_job_processing_status
        // CHECK constraint itself rejects an invalid raw status string -
        // independent of the fact that ProcessingStatus's Java enum type
        // already makes constructing an invalid RawJobProcessing
        // impossible at compile time. Nothing about the Java mapping is
        // loosened to make this test possible.
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO raw_job_processing (raw_job_posting_id, processing_status, normalizer_version)
                        VALUES (:postingId, 'NOT_A_REAL_STATUS', 'normalizer-v1')
                        """)
                .setParameter("postingId", postingId)
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }
}
