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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link JobLocation} JPA mapping - including its relationship
 * to {@link Job} and the {@code (job_id, city, state_region, country,
 * is_remote)} uniqueness rule - against the real PostgreSQL schema created
 * by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobLocationEntityTest {

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

    @Test
    void persistsForAnExistingJob() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-1");
        JobLocation location = new JobLocation(job, "Austin", "TX", "US", "78701", false, "Austin, TX");

        JobLocation persisted = entityManager.persistAndFlush(location);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-2");
        JobLocation location = new JobLocation(job, "Austin", "TX", "US", "78701", false, "Austin, TX");
        UUID id = entityManager.persistAndFlush(location).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        JobLocation reloaded = entityManager.find(JobLocation.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getCity()).isEqualTo("Austin");
        assertThat(reloaded.getStateRegion()).isEqualTo("TX");
        assertThat(reloaded.getCountry()).isEqualTo("US");
        assertThat(reloaded.getPostalCode()).isEqualTo("78701");
        assertThat(reloaded.isRemote()).isFalse();
        assertThat(reloaded.getRawLocationText()).isEqualTo("Austin, TX");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void jobRelationshipResolvesCorrectly() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-3");
        JobLocation location = new JobLocation(job, "Austin", "TX", "US", null, false, null);
        UUID id = entityManager.persistAndFlush(location).getId();

        entityManager.clear();
        JobLocation reloaded = entityManager.find(JobLocation.class, id);

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
        JobLocation orphan = new JobLocation(nonExistentJob, "Austin", "TX", "US", null, false, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleValidLocationsForOneJobSucceed() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-5");

        JobLocation austin = entityManager.persistAndFlush(
                new JobLocation(job, "Austin", "TX", "US", null, false, null));
        JobLocation remote = entityManager.persistAndFlush(
                new JobLocation(job, null, null, "US", null, true, "Remote (US)"));

        assertThat(austin.getId()).isNotNull();
        assertThat(remote.getId()).isNotNull();
        assertThat(austin.getId()).isNotEqualTo(remote.getId());
    }

    @Test
    void duplicateJobCityStateCountryRemoteCombinationFails() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-6");
        entityManager.persistAndFlush(new JobLocation(job, "Austin", "TX", "US", null, false, null));

        JobLocation duplicate = new JobLocation(job, "Austin", "TX", "US", "different-postal", false, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nullableFieldsBehaveAccordingToTheSchema() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-7");
        // city, state_region, postal_code, raw_location_text are nullable;
        // only country and is_remote are required.
        JobLocation minimal = new JobLocation(job, null, null, "US", null, true, null);
        UUID id = entityManager.persistAndFlush(minimal).getId();

        entityManager.clear();
        JobLocation reloaded = entityManager.find(JobLocation.class, id);

        assertThat(reloaded.getCity()).isNull();
        assertThat(reloaded.getStateRegion()).isNull();
        assertThat(reloaded.getPostalCode()).isNull();
        assertThat(reloaded.getRawLocationText()).isNull();
        // Required fields are still present:
        assertThat(reloaded.getCountry()).isEqualTo("US");
        assertThat(reloaded.isRemote()).isTrue();
    }

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-8");
        JobLocation location = new JobLocation(job, "Austin", "TX", "US", null, false, null);

        JobLocation persisted = entityManager.persistAndFlush(location);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void locationContentCanBeUpdatedLater() {
        // JobLocation has no setters by design, so - consistent with how
        // Job's own mutability was re-verified - this uses
        // ReflectionTestUtils to mutate the private fields directly,
        // proving Hibernate's dirty-checking honors the JPA "updatable"
        // mapping on the content fields, independent of whether any
        // service-level enrichment API exists yet (it doesn't).
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-9");
        JobLocation location = entityManager.persistAndFlush(
                new JobLocation(job, "Austin", "TX", "US", "78701", false, "Austin, TX"));
        UUID id = location.getId();

        ReflectionTestUtils.setField(location, "city", "Round Rock");
        ReflectionTestUtils.setField(location, "postalCode", "78664");
        entityManager.persistAndFlush(location);
        entityManager.clear();

        JobLocation reloaded = entityManager.find(JobLocation.class, id);
        assertThat(reloaded.getCity()).isEqualTo("Round Rock");
        assertThat(reloaded.getPostalCode()).isEqualTo("78664");
        // Unchanged fields remain as they were:
        assertThat(reloaded.getStateRegion()).isEqualTo("TX");
        assertThat(reloaded.getCountry()).isEqualTo("US");
    }

    // --- NULL-participation audit (V2): does the (job_id, city,
    // state_region, country, is_remote) unique constraint prevent
    // duplicates when city and/or state_region are NULL? Before
    // V2__fix_job_location_uniqueness.sql, PostgreSQL's default
    // never-equal NULL semantics let both cases below through as
    // "non-duplicates" - confirmed empirically at the time. V2 switched
    // the constraint to NULLS NOT DISTINCT, so these same two cases must
    // now be rejected. Compare with
    // duplicateJobCityStateCountryRemoteCombinationFails above (all five
    // columns non-null), which was already rejected before V2 and remains
    // rejected after. ---

    @Test
    void duplicateNullCityAndStateRegionCombinationFailsAfterV2() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-audit-1");
        entityManager.persistAndFlush(
                new JobLocation(job, null, null, "US", null, true, "Remote (US)"));

        // Same logical key: city = null, state_region = null,
        // country = "US", is_remote = true.
        JobLocation duplicate = new JobLocation(job, null, null, "US", null, true, "Remote (US) - duplicate");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void duplicateSameCityWithNullStateRegionCombinationFailsAfterV2() {
        Company company = persistCompany("Acme Corp", "acme corp");
        Job job = persistJob(company, "fp-audit-2");
        entityManager.persistAndFlush(
                new JobLocation(job, "Austin", null, "US", null, false, "Austin"));

        // Same logical key: city = "Austin", state_region = null,
        // country = "US", is_remote = false.
        JobLocation duplicate = new JobLocation(job, "Austin", null, "US", null, false, "Austin - duplicate");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }
}
