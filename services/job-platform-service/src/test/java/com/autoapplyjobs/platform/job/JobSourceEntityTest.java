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
 * Verifies the {@link JobSource} JPA mapping - including its relationship
 * to {@link Job} and the two partial-unique external-identity rules
 * ({@code uq_job_source_native_id}, {@code uq_job_source_url}) - against
 * the real PostgreSQL schema created by
 * {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobSourceEntityTest {

    @Autowired
    private TestEntityManager entityManager;

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

    // --- 1-4: basic mapping ---

    @Test
    void persistsForAnExistingJob() {
        Job job = newJob("fp-1");
        JobSource source = new JobSource(job, "linkedin", "ext-1", "https://linkedin.com/jobs/1",
                Instant.now(), Instant.now());

        JobSource persisted = entityManager.persistAndFlush(source);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Job job = newJob("fp-2");
        Instant firstSeenAt = Instant.now();
        Instant lastSeenAt = Instant.now();
        JobSource source = new JobSource(job, "linkedin", "ext-2", "https://linkedin.com/jobs/2",
                firstSeenAt, lastSeenAt);
        UUID id = entityManager.persistAndFlush(source).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        JobSource reloaded = entityManager.find(JobSource.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getSourceName()).isEqualTo("linkedin");
        assertThat(reloaded.getSourceNativeId()).isEqualTo("ext-2");
        assertThat(reloaded.getSourceUrl()).isEqualTo("https://linkedin.com/jobs/2");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void jobRelationshipResolvesCorrectly() {
        Job job = newJob("fp-3");
        JobSource source = new JobSource(job, "linkedin", "ext-3", "https://linkedin.com/jobs/3",
                Instant.now(), Instant.now());
        UUID id = entityManager.persistAndFlush(source).getId();

        entityManager.clear();
        JobSource reloaded = entityManager.find(JobSource.class, id);

        assertThat(reloaded.getJob()).isNotNull();
        assertThat(reloaded.getJob().getId()).isEqualTo(job.getId());
        assertThat(reloaded.getJob().getTitle()).isEqualTo("Backend Engineer");
    }

    @Test
    void violatesForeignKeyWithoutAValidJob() {
        // A proxy for a Job id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        Job nonExistentJob = entityManager.getEntityManager()
                .getReference(Job.class, UUID.randomUUID());
        JobSource orphan = new JobSource(nonExistentJob, "linkedin", "ext-4",
                "https://linkedin.com/jobs/4", Instant.now(), Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 5-7: native-ID identity (uq_job_source_native_id) ---

    @Test
    void sameSourceNameAndSameNonNullNativeIdFails() {
        Job job = newJob("fp-5");
        entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-5",
                "https://linkedin.com/jobs/5a", Instant.now(), Instant.now()));

        JobSource duplicate = new JobSource(job, "linkedin", "ext-5",
                "https://linkedin.com/jobs/5b", Instant.now(), Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void sameNativeIdUnderDifferentSourceNamesSucceeds() {
        Job job = newJob("fp-6");
        JobSource linkedin = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-6",
                "https://linkedin.com/jobs/6", Instant.now(), Instant.now()));
        JobSource indeed = entityManager.persistAndFlush(new JobSource(job, "indeed", "ext-6",
                "https://indeed.com/jobs/6", Instant.now(), Instant.now()));

        assertThat(linkedin.getId()).isNotEqualTo(indeed.getId());
    }

    @Test
    void differentNativeIdsUnderSameSourceSucceed() {
        Job job = newJob("fp-7");
        JobSource first = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-7a",
                "https://linkedin.com/jobs/7a", Instant.now(), Instant.now()));
        JobSource second = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-7b",
                "https://linkedin.com/jobs/7b", Instant.now(), Instant.now()));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    // --- 8-10: URL fallback identity (uq_job_source_url, only when
    // source_native_id IS NULL) ---

    @Test
    void whenNativeIdNullSameSourceNameAndSameUrlFails() {
        Job job = newJob("fp-8");
        entityManager.persistAndFlush(new JobSource(job, "linkedin", null,
                "https://linkedin.com/jobs/8", Instant.now(), Instant.now()));

        JobSource duplicate = new JobSource(job, "linkedin", null,
                "https://linkedin.com/jobs/8", Instant.now(), Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void whenNativeIdNullSameUrlUnderDifferentSourceNamesSucceeds() {
        Job job = newJob("fp-9");
        JobSource linkedin = entityManager.persistAndFlush(new JobSource(job, "linkedin", null,
                "https://mirror.example.com/jobs/9", Instant.now(), Instant.now()));
        JobSource indeed = entityManager.persistAndFlush(new JobSource(job, "indeed", null,
                "https://mirror.example.com/jobs/9", Instant.now(), Instant.now()));

        assertThat(linkedin.getId()).isNotEqualTo(indeed.getId());
    }

    @Test
    void whenNativeIdNullDifferentUrlsUnderSameSourceSucceed() {
        Job job = newJob("fp-10");
        JobSource first = entityManager.persistAndFlush(new JobSource(job, "linkedin", null,
                "https://linkedin.com/jobs/10a", Instant.now(), Instant.now()));
        JobSource second = entityManager.persistAndFlush(new JobSource(job, "linkedin", null,
                "https://linkedin.com/jobs/10b", Instant.now(), Instant.now()));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    // --- 11: native-ID precedence - what actually happens when two rows
    // share source_name + source_url but have different non-null
    // source_native_id values? uq_job_source_url only applies WHERE
    // source_native_id IS NULL, so once both rows have a native ID it
    // never checks source_url at all - only uq_job_source_native_id (keyed
    // on source_name + source_native_id, not source_url) applies, and
    // that doesn't collide either since the native IDs differ. ---

    @Test
    void sameSourceNameAndUrlWithDifferentNonNullNativeIdsBothSucceed() {
        Job job = newJob("fp-11");
        JobSource first = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-11a",
                "https://linkedin.com/jobs/11", Instant.now(), Instant.now()));
        // Same source_name AND same source_url as `first`, but a different
        // non-null native ID.
        JobSource second = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-11b",
                "https://linkedin.com/jobs/11", Instant.now(), Instant.now()));

        // Neither partial index rejects this: uq_job_source_url doesn't
        // apply (both rows have non-null source_native_id), and
        // uq_job_source_native_id doesn't collide (native IDs differ).
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getSourceUrl()).isEqualTo(second.getSourceUrl());
    }

    // --- 12-13: enrichment audit - a URL-identity row (native_id = null)
    // already exists; does inserting a *new* row with the same
    // source_name/source_url but a non-null native_id succeed, or does
    // PostgreSQL somehow recognize it as "the same posting, now
    // enriched"? See this step's final report for the conclusion. ---

    @Test
    void anEnrichedNativeIdRowDoesNotCollideWithTheExistingUrlIdentityRow() {
        Job job = newJob("fp-12");

        // 12: a URL-only-identity row - no native ID yet.
        JobSource urlOnly = entityManager.persistAndFlush(new JobSource(job, "linkedin", null,
                "https://linkedin.com/jobs/12", Instant.now(), Instant.now()));

        // 13: a second, brand-new row for the *same* source_name/source_url,
        // but now with a native ID - as if the same posting were
        // re-ingested and this time the source provided one.
        JobSource enriched = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-12",
                "https://linkedin.com/jobs/12", Instant.now(), Instant.now()));

        // PostgreSQL allows both rows to coexist as two separate JobSource
        // rows for what is, in reality, the same external posting -
        // neither partial index rejects the insert (see this step's final
        // report, "Result of the enrichment audit"). This is not a schema
        // bug to fix here; it means future ingestion/enrichment service
        // logic must look up and UPDATE the existing URL-identity row
        // in place, rather than relying on the database to prevent a
        // second row from being inserted.
        assertThat(urlOnly.getId()).isNotEqualTo(enriched.getId());
        assertThat(urlOnly.getSourceUrl()).isEqualTo(enriched.getSourceUrl());
    }

    // --- 14-17: mutability verification. JobSource has no setters by
    // design, so - consistent with how Job's and JobLocation's own
    // mutability were verified - these use ReflectionTestUtils to mutate
    // the private fields directly, proving Hibernate's dirty-checking
    // honors the JPA "updatable" mapping, independent of whether any
    // service-level enrichment/reassignment API exists yet (it doesn't). ---

    @Test
    void sourceNativeIdCanBeEnrichedFromNullToNonNull() {
        Job job = newJob("fp-14");
        JobSource source = entityManager.persistAndFlush(new JobSource(job, "linkedin", null,
                "https://linkedin.com/jobs/14", Instant.now(), Instant.now()));
        UUID id = source.getId();
        assertThat(source.getSourceNativeId()).isNull();

        ReflectionTestUtils.setField(source, "sourceNativeId", "ext-14-later");
        entityManager.persistAndFlush(source);
        entityManager.clear();

        JobSource reloaded = entityManager.find(JobSource.class, id);
        assertThat(reloaded.getSourceNativeId()).isEqualTo("ext-14-later");
    }

    @Test
    void mutatingSourceNameDoesNotChangeThePersistedValue() {
        // sourceName is now mapped updatable = false (resolved mutability
        // question - see the class Javadoc). ReflectionTestUtils can still
        // set the in-memory field - that's just Java - but this proves
        // Hibernate excludes the column from its UPDATE statement, so the
        // persisted value is unaffected.
        Job job = newJob("fp-18");
        JobSource source = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-18",
                "https://linkedin.com/jobs/18", Instant.now(), Instant.now()));
        UUID id = source.getId();

        ReflectionTestUtils.setField(source, "sourceName", "indeed");
        entityManager.persistAndFlush(source);
        entityManager.clear();

        JobSource reloaded = entityManager.find(JobSource.class, id);
        assertThat(reloaded.getSourceName()).isEqualTo("linkedin");
    }

    @Test
    void sourceUrlCanBeCorrectedAndPersisted() {
        Job job = newJob("fp-19");
        JobSource source = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-19",
                "https://linkedin.com/jobs/19-old", Instant.now(), Instant.now()));
        UUID id = source.getId();

        ReflectionTestUtils.setField(source, "sourceUrl", "https://linkedin.com/jobs/19-corrected");
        entityManager.persistAndFlush(source);
        entityManager.clear();

        JobSource reloaded = entityManager.find(JobSource.class, id);
        assertThat(reloaded.getSourceUrl()).isEqualTo("https://linkedin.com/jobs/19-corrected");
    }

    @Test
    void jobSourceCanBeReassociatedWithAnotherExistingCanonicalJob() {
        Job originalJob = newJob("fp-15a");
        Job correctedJob = newJob("fp-15b");
        JobSource source = entityManager.persistAndFlush(new JobSource(originalJob, "linkedin", "ext-15",
                "https://linkedin.com/jobs/15", Instant.now(), Instant.now()));
        UUID id = source.getId();

        ReflectionTestUtils.setField(source, "job", correctedJob);
        entityManager.persistAndFlush(source);
        entityManager.clear();

        JobSource reloaded = entityManager.find(JobSource.class, id);
        assertThat(reloaded.getJob().getId()).isEqualTo(correctedJob.getId());
        assertThat(reloaded.getJob().getId()).isNotEqualTo(originalJob.getId());
    }

    @Test
    void lastSeenAtCanBeUpdatedWhileFirstSeenAtRemainsUnchanged() {
        Job job = newJob("fp-16");
        Instant firstSeenAt = Instant.now().minusSeconds(3600);
        Instant originalLastSeenAt = Instant.now().minusSeconds(1800);
        JobSource source = entityManager.persistAndFlush(new JobSource(job, "linkedin", "ext-16",
                "https://linkedin.com/jobs/16", firstSeenAt, originalLastSeenAt));
        UUID id = source.getId();

        Instant newLastSeenAt = Instant.now();
        ReflectionTestUtils.setField(source, "lastSeenAt", newLastSeenAt);
        entityManager.persistAndFlush(source);
        entityManager.clear();

        JobSource reloaded = entityManager.find(JobSource.class, id);
        assertThat(reloaded.getLastSeenAt()).isCloseTo(newLastSeenAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getFirstSeenAt()).isCloseTo(firstSeenAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Job job = newJob("fp-17");
        JobSource source = new JobSource(job, "linkedin", "ext-17", "https://linkedin.com/jobs/17",
                Instant.now(), Instant.now());

        JobSource persisted = entityManager.persistAndFlush(source);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }
}
