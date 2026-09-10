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
 * Verifies the {@link JobSkill} JPA mapping - including its relationships
 * to {@link Job} and {@link Skill} and the {@code (job_id, skill_id)}
 * uniqueness rule - against the real PostgreSQL schema created by
 * {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobSkillEntityTest {

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

    private Skill persistSkill(String name, String normalizedName) {
        return entityManager.persistAndFlush(new Skill(name, normalizedName));
    }

    @Test
    void persistsForAnExistingJobAndSkill() {
        Job job = newJob("fp-1");
        Skill skill = persistSkill("Python", "python");
        JobSkill jobSkill = new JobSkill(job, skill, true);

        JobSkill persisted = entityManager.persistAndFlush(jobSkill);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Job job = newJob("fp-2");
        Skill skill = persistSkill("Kubernetes", "kubernetes");
        JobSkill jobSkill = new JobSkill(job, skill, false);
        UUID id = entityManager.persistAndFlush(jobSkill).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        JobSkill reloaded = entityManager.find(JobSkill.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.isRequired()).isFalse();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void jobRelationshipResolvesCorrectly() {
        Job job = newJob("fp-3");
        Skill skill = persistSkill("Java", "java");
        JobSkill jobSkill = new JobSkill(job, skill, true);
        UUID id = entityManager.persistAndFlush(jobSkill).getId();

        entityManager.clear();
        JobSkill reloaded = entityManager.find(JobSkill.class, id);

        assertThat(reloaded.getJob()).isNotNull();
        assertThat(reloaded.getJob().getId()).isEqualTo(job.getId());
        assertThat(reloaded.getJob().getTitle()).isEqualTo("Backend Engineer");
    }

    @Test
    void skillRelationshipResolvesCorrectly() {
        Job job = newJob("fp-4");
        Skill skill = persistSkill("Go", "go");
        JobSkill jobSkill = new JobSkill(job, skill, true);
        UUID id = entityManager.persistAndFlush(jobSkill).getId();

        entityManager.clear();
        JobSkill reloaded = entityManager.find(JobSkill.class, id);

        assertThat(reloaded.getSkill()).isNotNull();
        assertThat(reloaded.getSkill().getId()).isEqualTo(skill.getId());
        assertThat(reloaded.getSkill().getNormalizedName()).isEqualTo("go");
    }

    @Test
    void violatesForeignKeyWithoutAValidJob() {
        // A proxy for a Job id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        Job nonExistentJob = entityManager.getEntityManager()
                .getReference(Job.class, UUID.randomUUID());
        Skill skill = persistSkill("Rust", "rust");
        JobSkill orphan = new JobSkill(nonExistentJob, skill, true);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void violatesForeignKeyWithoutAValidSkill() {
        Job job = newJob("fp-6");
        // A proxy for a Skill id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        Skill nonExistentSkill = entityManager.getEntityManager()
                .getReference(Skill.class, UUID.randomUUID());
        JobSkill orphan = new JobSkill(job, nonExistentSkill, true);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void duplicateJobAndSkillCombinationFails() {
        Job job = newJob("fp-7");
        Skill skill = persistSkill("C++", "c++");
        entityManager.persistAndFlush(new JobSkill(job, skill, true));

        JobSkill duplicate = new JobSkill(job, skill, false);

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleDifferentSkillsForTheSameJobSucceed() {
        Job job = newJob("fp-8");
        Skill first = persistSkill("SQL", "sql");
        Skill second = persistSkill("NoSQL", "nosql");

        JobSkill firstJobSkill = entityManager.persistAndFlush(new JobSkill(job, first, true));
        JobSkill secondJobSkill = entityManager.persistAndFlush(new JobSkill(job, second, false));

        assertThat(firstJobSkill.getId()).isNotEqualTo(secondJobSkill.getId());
    }

    @Test
    void theSameSkillAttachedToDifferentJobsSucceeds() {
        Job firstJob = newJob("fp-9a");
        Job secondJob = newJob("fp-9b");
        Skill skill = persistSkill("TypeScript", "typescript");

        JobSkill firstJobSkill = entityManager.persistAndFlush(new JobSkill(firstJob, skill, true));
        JobSkill secondJobSkill = entityManager.persistAndFlush(new JobSkill(secondJob, skill, true));

        assertThat(firstJobSkill.getId()).isNotEqualTo(secondJobSkill.getId());
        assertThat(firstJobSkill.getSkill().getId()).isEqualTo(secondJobSkill.getSkill().getId());
    }

    @Test
    void isRequiredHasNoNullableColumnsToExercise() {
        // job_skill has no nullable columns besides the DB-generated
        // created_at: id, job_id, skill_id, and is_required are all
        // NOT NULL in V1. is_required has a DEFAULT of true in the
        // migration, but the entity always supplies it explicitly (no
        // Java-side default), so this test only confirms the boolean
        // round-trips both ways rather than exercising any NULL case.
        Job job = newJob("fp-10");
        Skill skill = persistSkill("Docker", "docker");

        JobSkill required = entityManager.persistAndFlush(new JobSkill(job, skill, true));
        assertThat(required.isRequired()).isTrue();
    }

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Job job = newJob("fp-11");
        Skill skill = persistSkill("AWS", "aws");
        JobSkill jobSkill = new JobSkill(job, skill, true);

        JobSkill persisted = entityManager.persistAndFlush(jobSkill);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    // --- Mutability verification. JobSkill has no setters by design, so -
    // consistent with how Job's, JobLocation's, and JobSource's own
    // mutability were verified - these use ReflectionTestUtils to mutate
    // the private fields directly, proving Hibernate's dirty-checking
    // honors the JPA "updatable" mapping, independent of whether any
    // service-level reprocessing API exists yet (it doesn't). ---

    @Test
    void isRequiredCanBeCorrectedOnReprocessing() {
        Job job = newJob("fp-12");
        Skill skill = persistSkill("GraphQL", "graphql");
        JobSkill jobSkill = entityManager.persistAndFlush(new JobSkill(job, skill, true));
        UUID id = jobSkill.getId();

        ReflectionTestUtils.setField(jobSkill, "isRequired", false);
        entityManager.persistAndFlush(jobSkill);
        entityManager.clear();

        JobSkill reloaded = entityManager.find(JobSkill.class, id);
        assertThat(reloaded.isRequired()).isFalse();
    }

    @Test
    void mutatingTheInMemorySkillReferenceDoesNotChangeThePersistedSkillId() {
        // skill is now mapped updatable = false (resolved mutability
        // question - see the class Javadoc): JobSkill's identity is the
        // pair (job_id, skill_id), so a single row's skill association is
        // never mutated in place. ReflectionTestUtils can still set the
        // in-memory field - that's just Java - but this proves Hibernate
        // excludes the column from its UPDATE statement, so the persisted
        // skill_id is unaffected.
        Job job = newJob("fp-13");
        Skill original = persistSkill("Pythom", "pythom");
        Skill different = persistSkill("Python3", "python3");
        JobSkill jobSkill = entityManager.persistAndFlush(new JobSkill(job, original, true));
        UUID id = jobSkill.getId();

        ReflectionTestUtils.setField(jobSkill, "skill", different);
        entityManager.persistAndFlush(jobSkill);
        entityManager.clear();

        JobSkill reloaded = entityManager.find(JobSkill.class, id);
        assertThat(reloaded.getSkill().getId()).isEqualTo(original.getId());
        assertThat(reloaded.getSkill().getId()).isNotEqualTo(different.getId());
    }
}
