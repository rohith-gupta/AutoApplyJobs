package com.autoapplyjobs.platform.matching;

import com.autoapplyjobs.platform.company.Company;
import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.job.JobStatus;
import com.autoapplyjobs.platform.resume.Resume;
import com.autoapplyjobs.platform.user.User;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
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
 * Verifies the {@link JobMatch} JPA mapping - including its relationships
 * to {@link Job} and {@link Resume}, the
 * {@code (job_id, resume_id, algorithm_version)} identity/uniqueness rule,
 * {@link BigDecimal} score precision, and the {@code 0.00}-{@code 100.00}
 * score-range {@code CHECK} constraints added by
 * {@code V4__constrain_job_match_scores.sql} - against the real
 * PostgreSQL schema created by {@code db/migration/V1__initial_schema.sql}
 * as amended through {@code V4}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobMatchEntityTest {

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

    private User persistUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    private Resume persistResume(User user, int versionNumber) {
        return entityManager.persistAndFlush(new Resume(user, versionNumber,
                "file://r" + versionNumber, null, false, Instant.now()));
    }

    private Resume newResume(String emailPrefix) {
        User user = persistUser(emailPrefix + "@example.com");
        return persistResume(user, 1);
    }

    // --- 1-4: basic mapping ---

    @Test
    void persistsForAnExistingJobAndResume() {
        Job job = newJob("fp-1");
        Resume resume = newResume("match-1");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("75.50"),
                null, null, null, null, null, null, null, Instant.now());

        JobMatch persisted = entityManager.persistAndFlush(match);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Job job = newJob("fp-2");
        Resume resume = newResume("match-2");
        Instant computedAt = Instant.now();
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("82.25"),
                new BigDecimal("90.00"), new BigDecimal("70.50"), new BigDecimal("88.00"),
                new BigDecimal("60.00"), new BigDecimal("95.00"), new BigDecimal("100.00"),
                new BigDecimal("55.75"), computedAt);
        UUID id = entityManager.persistAndFlush(match).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        JobMatch reloaded = entityManager.find(JobMatch.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getAlgorithmVersion()).isEqualTo("MATCH_V1");
        assertThat(reloaded.getOverallScore()).isEqualByComparingTo("82.25");
        assertThat(reloaded.getSkillsScore()).isEqualByComparingTo("90.00");
        assertThat(reloaded.getExperienceScore()).isEqualByComparingTo("70.50");
        assertThat(reloaded.getTitleScore()).isEqualByComparingTo("88.00");
        assertThat(reloaded.getResponsibilityScore()).isEqualByComparingTo("60.00");
        assertThat(reloaded.getEducationScore()).isEqualByComparingTo("95.00");
        assertThat(reloaded.getLocationScore()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getPreferenceScore()).isEqualByComparingTo("55.75");
        assertThat(reloaded.getComputedAt()).isCloseTo(computedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void jobRelationshipResolvesCorrectly() {
        Job job = newJob("fp-3");
        Resume resume = newResume("match-3");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now());
        UUID id = entityManager.persistAndFlush(match).getId();

        entityManager.clear();
        JobMatch reloaded = entityManager.find(JobMatch.class, id);

        assertThat(reloaded.getJob()).isNotNull();
        assertThat(reloaded.getJob().getId()).isEqualTo(job.getId());
        assertThat(reloaded.getJob().getTitle()).isEqualTo("Backend Engineer");
    }

    @Test
    void resumeRelationshipResolvesCorrectly() {
        Job job = newJob("fp-4");
        Resume resume = newResume("match-4");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now());
        UUID id = entityManager.persistAndFlush(match).getId();

        entityManager.clear();
        JobMatch reloaded = entityManager.find(JobMatch.class, id);

        assertThat(reloaded.getResume()).isNotNull();
        assertThat(reloaded.getResume().getId()).isEqualTo(resume.getId());
        assertThat(reloaded.getResume().getVersionNumber()).isEqualTo(1);
    }

    // --- 5-6: FK violations ---

    @Test
    void violatesForeignKeyWithoutAValidJob() {
        // A proxy for a Job id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        Job nonExistentJob = entityManager.getEntityManager()
                .getReference(Job.class, UUID.randomUUID());
        Resume resume = newResume("match-5");
        JobMatch orphan = new JobMatch(nonExistentJob, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void violatesForeignKeyWithoutAValidResume() {
        Job job = newJob("fp-6");
        // A proxy for a Resume id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Resume nonExistentResume = entityManager.getEntityManager()
                .getReference(Resume.class, UUID.randomUUID());
        JobMatch orphan = new JobMatch(job, nonExistentResume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 7-10: identity/uniqueness ---

    @Test
    void duplicateJobResumeAlgorithmVersionFails() {
        Job job = newJob("fp-7");
        Resume resume = newResume("match-7");
        entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now()));

        JobMatch duplicate = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("60.00"),
                null, null, null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theSameJobAndResumeWithADifferentAlgorithmVersionSucceeds() {
        Job job = newJob("fp-8");
        Resume resume = newResume("match-8");

        JobMatch v1 = entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null, Instant.now()));
        JobMatch v2 = entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V2",
                new BigDecimal("65.00"), null, null, null, null, null, null, null, Instant.now()));

        assertThat(v1.getId()).isNotEqualTo(v2.getId());
        assertThat(v1.getAlgorithmVersion()).isNotEqualTo(v2.getAlgorithmVersion());
    }

    @Test
    void theSameJobMatchedAgainstDifferentResumeVersionsSucceeds() {
        Job job = newJob("fp-9");
        User user = persistUser("match-9@example.com");
        Resume firstVersion = persistResume(user, 1);
        Resume secondVersion = persistResume(user, 2);

        JobMatch first = entityManager.persistAndFlush(new JobMatch(job, firstVersion, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null, Instant.now()));
        JobMatch second = entityManager.persistAndFlush(new JobMatch(job, secondVersion, "MATCH_V1",
                new BigDecimal("55.00"), null, null, null, null, null, null, null, Instant.now()));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getResume().getId()).isNotEqualTo(second.getResume().getId());
    }

    @Test
    void theSameResumeMatchedAgainstDifferentJobsSucceeds() {
        Resume resume = newResume("match-10");
        Job firstJob = newJob("fp-10a");
        Job secondJob = newJob("fp-10b");

        JobMatch first = entityManager.persistAndFlush(new JobMatch(firstJob, resume, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null, Instant.now()));
        JobMatch second = entityManager.persistAndFlush(new JobMatch(secondJob, resume, "MATCH_V1",
                new BigDecimal("55.00"), null, null, null, null, null, null, null, Instant.now()));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getJob().getId()).isNotEqualTo(second.getJob().getId());
    }

    // --- 11: BigDecimal precision, no floating-point conversion ---

    @Test
    void scoreFieldsRoundTripUsingBigDecimalWithoutUnintendedFloatingPointConversion() {
        Job job = newJob("fp-11");
        Resume resume = newResume("match-11");
        // A value that is not exactly representable in binary floating
        // point (0.1 + 0.2 != 0.3 in double arithmetic) - proves the
        // mapping is genuinely decimal, not silently routed through a
        // float/double anywhere.
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("33.10"),
                new BigDecimal("10.20"), null, null, null, null, null, null, Instant.now());
        UUID id = entityManager.persistAndFlush(match).getId();

        entityManager.clear();
        JobMatch reloaded = entityManager.find(JobMatch.class, id);

        assertThat(reloaded.getOverallScore()).isEqualByComparingTo("33.10");
        assertThat(reloaded.getSkillsScore()).isEqualByComparingTo("10.20");
        // Scale is preserved as NUMERIC(5,2) - not silently normalized.
        assertThat(reloaded.getOverallScore().scale()).isEqualTo(2);
    }

    // --- 12-13: mutable score/result state. JobMatch has no setters by
    // design, so - consistent with how every other entity's mutability
    // was verified - these use ReflectionTestUtils to mutate the private
    // fields directly, proving Hibernate's dirty-checking honors the JPA
    // "updatable" mapping, independent of whether any service-level
    // recompute logic exists yet (it doesn't). ---

    @Test
    void scoreFieldsCanBeRecalculatedForTheExistingJobMatchRow() {
        Job job = newJob("fp-12");
        Resume resume = newResume("match-12");
        JobMatch match = entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1",
                new BigDecimal("50.00"), new BigDecimal("40.00"), null, null, null, null, null,
                null, Instant.now()));
        UUID id = match.getId();

        ReflectionTestUtils.setField(match, "overallScore", new BigDecimal("77.25"));
        ReflectionTestUtils.setField(match, "skillsScore", new BigDecimal("80.00"));
        ReflectionTestUtils.setField(match, "experienceScore", new BigDecimal("60.50"));
        entityManager.persistAndFlush(match);
        entityManager.clear();

        JobMatch reloaded = entityManager.find(JobMatch.class, id);
        assertThat(reloaded.getOverallScore()).isEqualByComparingTo("77.25");
        assertThat(reloaded.getSkillsScore()).isEqualByComparingTo("80.00");
        assertThat(reloaded.getExperienceScore()).isEqualByComparingTo("60.50");
        // Still the same row - recomputation upserts in place, no new row.
        assertThat(reloaded.getId()).isEqualTo(id);
    }

    @Test
    void computedAtCanBeUpdatedWhenRecalculationOccurs() {
        Job job = newJob("fp-13");
        Resume resume = newResume("match-13");
        Instant originalComputedAt = Instant.now().minusSeconds(3600);
        JobMatch match = entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null,
                originalComputedAt));
        UUID id = match.getId();

        Instant recomputedAt = Instant.now();
        ReflectionTestUtils.setField(match, "overallScore", new BigDecimal("65.00"));
        ReflectionTestUtils.setField(match, "computedAt", recomputedAt);
        entityManager.persistAndFlush(match);
        entityManager.clear();

        JobMatch reloaded = entityManager.find(JobMatch.class, id);
        assertThat(reloaded.getComputedAt()).isCloseTo(recomputedAt, within(1, ChronoUnit.MICROS));
        assertThat(reloaded.getComputedAt()).isNotEqualTo(originalComputedAt);
    }

    // --- 14-16: identity-field immutability. Mutating the in-memory
    // field is just Java - these prove Hibernate excludes the columns
    // from its UPDATE statement, so the persisted values are unaffected. ---

    @Test
    void jobIdCannotBeChangedThroughJpa() {
        Job originalJob = newJob("fp-14a");
        Job otherJob = newJob("fp-14b");
        Resume resume = newResume("match-14");
        JobMatch match = entityManager.persistAndFlush(new JobMatch(originalJob, resume, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null, Instant.now()));
        UUID id = match.getId();

        ReflectionTestUtils.setField(match, "job", otherJob);
        entityManager.persistAndFlush(match);
        entityManager.clear();

        assertThat(entityManager.find(JobMatch.class, id).getJob().getId())
                .isEqualTo(originalJob.getId());
    }

    @Test
    void resumeIdCannotBeChangedThroughJpa() {
        Job job = newJob("fp-15");
        User user = persistUser("match-15@example.com");
        Resume originalResume = persistResume(user, 1);
        Resume otherResume = persistResume(user, 2);
        JobMatch match = entityManager.persistAndFlush(new JobMatch(job, originalResume, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null, Instant.now()));
        UUID id = match.getId();

        ReflectionTestUtils.setField(match, "resume", otherResume);
        entityManager.persistAndFlush(match);
        entityManager.clear();

        assertThat(entityManager.find(JobMatch.class, id).getResume().getId())
                .isEqualTo(originalResume.getId());
    }

    @Test
    void algorithmVersionCannotBeChangedThroughJpa() {
        Job job = newJob("fp-16");
        Resume resume = newResume("match-16");
        JobMatch match = entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1",
                new BigDecimal("50.00"), null, null, null, null, null, null, null, Instant.now()));
        UUID id = match.getId();

        ReflectionTestUtils.setField(match, "algorithmVersion", "MATCH_V2");
        entityManager.persistAndFlush(match);
        entityManager.clear();

        assertThat(entityManager.find(JobMatch.class, id).getAlgorithmVersion()).isEqualTo("MATCH_V1");
    }

    // --- 17: database-generated fields ---

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Job job = newJob("fp-17");
        Resume resume = newResume("match-17");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now());

        JobMatch persisted = entityManager.persistAndFlush(match);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    // --- 18: NUMERIC(5,2) precision is still enforced independently of
    // the new 0-100 range constraints added by V4 ---

    @Test
    void aValueExceedingNumericFivePrecisionTwoIsRejectedByPostgres() {
        // NUMERIC(5,2) allows at most 3 digits before the decimal point
        // (5 total precision - 2 scale). This is a separate, pre-existing
        // constraint from the 0-100 range added by V4 below - both are
        // enforced, independently, by PostgreSQL.
        Job job = newJob("fp-18b");
        Resume resume = newResume("match-18b");
        JobMatch tooLarge = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("1000.00"),
                null, null, null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(tooLarge))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 19-27: score-range constraints added by
    // V4__constrain_job_match_scores.sql. Every score - overall_score and
    // all seven sub-scores - must be a normalized percentage in
    // [0.00, 100.00]; NULL sub-scores remain valid regardless (a NULL
    // comparison is UNKNOWN, not FALSE, under SQL three-valued logic, so
    // PostgreSQL's CHECK does not reject it). ---

    @Test
    void overallScoreOfZeroSucceeds() {
        Job job = newJob("fp-19");
        Resume resume = newResume("match-19");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("0.00"),
                null, null, null, null, null, null, null, Instant.now());

        JobMatch persisted = entityManager.persistAndFlush(match);

        assertThat(persisted.getOverallScore()).isEqualByComparingTo("0.00");
    }

    @Test
    void overallScoreOfOneHundredSucceeds() {
        Job job = newJob("fp-20");
        Resume resume = newResume("match-20");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("100.00"),
                null, null, null, null, null, null, null, Instant.now());

        JobMatch persisted = entityManager.persistAndFlush(match);

        assertThat(persisted.getOverallScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void overallScoreBelowZeroFails() {
        Job job = newJob("fp-21");
        Resume resume = newResume("match-21");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("-0.01"),
                null, null, null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(match))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void overallScoreAboveOneHundredFails() {
        Job job = newJob("fp-22");
        Resume resume = newResume("match-22");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("100.01"),
                null, null, null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(match))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void nullableSubScoresCanStillBeNull() {
        // overall_score is required and in-range; every sub-score is
        // left NULL - the 0-100 range constraints must not reject that.
        Job job = newJob("fp-23");
        Resume resume = newResume("match-23");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("42.00"),
                null, null, null, null, null, null, null, Instant.now());
        UUID id = entityManager.persistAndFlush(match).getId();

        entityManager.clear();
        JobMatch reloaded = entityManager.find(JobMatch.class, id);

        assertThat(reloaded.getSkillsScore()).isNull();
        assertThat(reloaded.getExperienceScore()).isNull();
        assertThat(reloaded.getTitleScore()).isNull();
        assertThat(reloaded.getResponsibilityScore()).isNull();
        assertThat(reloaded.getEducationScore()).isNull();
        assertThat(reloaded.getLocationScore()).isNull();
        assertThat(reloaded.getPreferenceScore()).isNull();
    }

    @Test
    void aSubScoreOfZeroSucceeds() {
        Job job = newJob("fp-24");
        Resume resume = newResume("match-24");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                new BigDecimal("0.00"), null, null, null, null, null, null, Instant.now());

        JobMatch persisted = entityManager.persistAndFlush(match);

        assertThat(persisted.getSkillsScore()).isEqualByComparingTo("0.00");
    }

    @Test
    void aSubScoreOfOneHundredSucceeds() {
        Job job = newJob("fp-25");
        Resume resume = newResume("match-25");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                new BigDecimal("100.00"), null, null, null, null, null, null, Instant.now());

        JobMatch persisted = entityManager.persistAndFlush(match);

        assertThat(persisted.getSkillsScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void aSubScoreBelowZeroFails() {
        Job job = newJob("fp-26");
        Resume resume = newResume("match-26");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, new BigDecimal("-5.00"), null, null, null, null, null, Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(match))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aSubScoreAboveOneHundredFails() {
        Job job = newJob("fp-27");
        Resume resume = newResume("match-27");
        JobMatch match = new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, new BigDecimal("105.00"), Instant.now());

        assertThatThrownBy(() -> entityManager.persistAndFlush(match))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void normalInRangeScoreRecalculationStillSucceeds() {
        // Recalculation (mutable score fields, already proven above by
        // scoreFieldsCanBeRecalculatedForTheExistingJobMatchRow) must
        // still work for ordinary in-range values after V4 adds the
        // range constraints - proven here end to end with realistic
        // values across every score column.
        Job job = newJob("fp-28");
        Resume resume = newResume("match-28");
        JobMatch match = entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1",
                new BigDecimal("40.00"), new BigDecimal("35.00"), null, null, null, null, null,
                null, Instant.now()));
        UUID id = match.getId();

        ReflectionTestUtils.setField(match, "overallScore", new BigDecimal("81.75"));
        ReflectionTestUtils.setField(match, "skillsScore", new BigDecimal("90.00"));
        ReflectionTestUtils.setField(match, "experienceScore", new BigDecimal("70.25"));
        ReflectionTestUtils.setField(match, "titleScore", new BigDecimal("60.00"));
        ReflectionTestUtils.setField(match, "responsibilityScore", new BigDecimal("55.50"));
        ReflectionTestUtils.setField(match, "educationScore", new BigDecimal("100.00"));
        ReflectionTestUtils.setField(match, "locationScore", new BigDecimal("0.00"));
        ReflectionTestUtils.setField(match, "preferenceScore", new BigDecimal("45.00"));
        entityManager.persistAndFlush(match);
        entityManager.clear();

        JobMatch reloaded = entityManager.find(JobMatch.class, id);
        assertThat(reloaded.getOverallScore()).isEqualByComparingTo("81.75");
        assertThat(reloaded.getSkillsScore()).isEqualByComparingTo("90.00");
        assertThat(reloaded.getExperienceScore()).isEqualByComparingTo("70.25");
        assertThat(reloaded.getTitleScore()).isEqualByComparingTo("60.00");
        assertThat(reloaded.getResponsibilityScore()).isEqualByComparingTo("55.50");
        assertThat(reloaded.getEducationScore()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getLocationScore()).isEqualByComparingTo("0.00");
        assertThat(reloaded.getPreferenceScore()).isEqualByComparingTo("45.00");
    }

    // --- 28-29: prove PostgreSQL's CHECK constraints directly via native
    // SQL, bypassing the JobMatch entity entirely - independent proof
    // that enforcement lives in the database itself, not in any
    // (nonexistent) Java-side validation. ---

    @Test
    void aNativeInsertWithAnOutOfRangeOverallScoreIsRejectedByPostgres() {
        Job job = newJob("fp-29");
        Resume resume = newResume("match-29");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO job_match (job_id, resume_id, algorithm_version, overall_score, computed_at)
                        VALUES (:jobId, :resumeId, 'MATCH_V1', 150.00, now())
                        """)
                .setParameter("jobId", job.getId())
                .setParameter("resumeId", resume.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aNativeInsertWithAnOutOfRangeSubScoreIsRejectedByPostgres() {
        Job job = newJob("fp-30");
        Resume resume = newResume("match-30");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO job_match (job_id, resume_id, algorithm_version, overall_score, skills_score, computed_at)
                        VALUES (:jobId, :resumeId, 'MATCH_V1', 50.00, -10.00, now())
                        """)
                .setParameter("jobId", job.getId())
                .setParameter("resumeId", resume.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aNativeInsertWithANullSubScoreStillSucceedsAgainstTheRangeConstraints() {
        // Direct proof that the CHECK constraints don't reject NULL
        // sub-scores, independent of the JPA mapping.
        Job job = newJob("fp-31");
        Resume resume = newResume("match-31");

        Object insertedId = entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO job_match (job_id, resume_id, algorithm_version, overall_score, skills_score, computed_at)
                        VALUES (:jobId, :resumeId, 'MATCH_V1', 50.00, NULL, now())
                        RETURNING id
                        """)
                .setParameter("jobId", job.getId())
                .setParameter("resumeId", resume.getId())
                .getSingleResult();

        assertThat(insertedId).isNotNull();
    }
}
