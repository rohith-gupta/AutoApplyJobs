package com.autoapplyjobs.platform.matching;

import com.autoapplyjobs.platform.company.Company;
import com.autoapplyjobs.platform.job.Job;
import com.autoapplyjobs.platform.job.JobStatus;
import com.autoapplyjobs.platform.job.Skill;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link JobMatchSkill} JPA mapping - including its
 * relationships to {@link JobMatch} and {@link Skill}, the
 * {@code (job_match_id, skill_id)} identity/uniqueness rule, the
 * {@link MatchStatus} enum mapping, and the {@code weight >= 0} guardrail
 * added by {@code V5__constrain_job_match_skill_weight.sql} - against the
 * real PostgreSQL schema created by {@code db/migration/V1__initial_schema.sql}
 * as amended through {@code V5}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JobMatchSkillEntityTest {

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

    private JobMatch newJobMatch(String suffix) {
        Job job = newJob("fp-" + suffix);
        Resume resume = newResume("match-" + suffix);
        return entityManager.persistAndFlush(new JobMatch(job, resume, "MATCH_V1", new BigDecimal("50.00"),
                null, null, null, null, null, null, null, Instant.now()));
    }

    private Skill persistSkill(String name, String normalizedName) {
        return entityManager.persistAndFlush(new Skill(name, normalizedName));
    }

    // --- 1-2: basic mapping ---

    @Test
    void persistsForAnExistingJobMatchAndSkill() {
        JobMatch jobMatch = newJobMatch("1");
        Skill skill = persistSkill("Python", "python");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED,
                new BigDecimal("40.00"));

        JobMatchSkill persisted = entityManager.persistAndFlush(jobMatchSkill);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        JobMatch jobMatch = newJobMatch("2");
        Skill skill = persistSkill("Kubernetes", "kubernetes");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.PARTIAL,
                new BigDecimal("15.50"));
        UUID id = entityManager.persistAndFlush(jobMatchSkill).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        JobMatchSkill reloaded = entityManager.find(JobMatchSkill.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getMatchStatus()).isEqualTo(MatchStatus.PARTIAL);
        assertThat(reloaded.getWeight()).isEqualByComparingTo("15.50");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    // --- 3-4: relationships ---

    @Test
    void jobMatchRelationshipResolvesCorrectly() {
        JobMatch jobMatch = newJobMatch("3");
        Skill skill = persistSkill("Java", "java");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED, null);
        UUID id = entityManager.persistAndFlush(jobMatchSkill).getId();

        entityManager.clear();
        JobMatchSkill reloaded = entityManager.find(JobMatchSkill.class, id);

        assertThat(reloaded.getJobMatch()).isNotNull();
        assertThat(reloaded.getJobMatch().getId()).isEqualTo(jobMatch.getId());
        assertThat(reloaded.getJobMatch().getAlgorithmVersion()).isEqualTo("MATCH_V1");
    }

    @Test
    void skillRelationshipResolvesCorrectly() {
        JobMatch jobMatch = newJobMatch("4");
        Skill skill = persistSkill("Go", "go");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED, null);
        UUID id = entityManager.persistAndFlush(jobMatchSkill).getId();

        entityManager.clear();
        JobMatchSkill reloaded = entityManager.find(JobMatchSkill.class, id);

        assertThat(reloaded.getSkill()).isNotNull();
        assertThat(reloaded.getSkill().getId()).isEqualTo(skill.getId());
        assertThat(reloaded.getSkill().getNormalizedName()).isEqualTo("go");
    }

    // --- 5-6: FK violations ---

    @Test
    void violatesForeignKeyWithoutAValidJobMatch() {
        // A proxy for a JobMatch id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        JobMatch nonExistentJobMatch = entityManager.getEntityManager()
                .getReference(JobMatch.class, UUID.randomUUID());
        Skill skill = persistSkill("Rust", "rust");
        JobMatchSkill orphan = new JobMatchSkill(nonExistentJobMatch, skill, MatchStatus.MATCHED, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void violatesForeignKeyWithoutAValidSkill() {
        JobMatch jobMatch = newJobMatch("6");
        // A proxy for a Skill id that was never persisted - no DB hit
        // yet, so this only fails when the FK is actually checked on flush.
        Skill nonExistentSkill = entityManager.getEntityManager()
                .getReference(Skill.class, UUID.randomUUID());
        JobMatchSkill orphan = new JobMatchSkill(jobMatch, nonExistentSkill, MatchStatus.MATCHED, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 7-9: identity/uniqueness ---

    @Test
    void duplicateJobMatchAndSkillFails() {
        JobMatch jobMatch = newJobMatch("7");
        Skill skill = persistSkill("C++", "c++");
        entityManager.persistAndFlush(new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED, null));

        JobMatchSkill duplicate = new JobMatchSkill(jobMatch, skill, MatchStatus.MISSING, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void multipleSkillsForTheSameJobMatchSucceed() {
        JobMatch jobMatch = newJobMatch("8");
        Skill first = persistSkill("SQL", "sql");
        Skill second = persistSkill("NoSQL", "nosql");

        JobMatchSkill firstRow = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, first, MatchStatus.MATCHED, null));
        JobMatchSkill secondRow = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, second, MatchStatus.PARTIAL, null));

        assertThat(firstRow.getId()).isNotEqualTo(secondRow.getId());
    }

    @Test
    void theSameSkillAcrossDifferentJobMatchRowsSucceeds() {
        JobMatch firstJobMatch = newJobMatch("9a");
        JobMatch secondJobMatch = newJobMatch("9b");
        Skill skill = persistSkill("TypeScript", "typescript");

        JobMatchSkill firstRow = entityManager.persistAndFlush(
                new JobMatchSkill(firstJobMatch, skill, MatchStatus.MATCHED, null));
        JobMatchSkill secondRow = entityManager.persistAndFlush(
                new JobMatchSkill(secondJobMatch, skill, MatchStatus.MISSING, null));

        assertThat(firstRow.getId()).isNotEqualTo(secondRow.getId());
        assertThat(firstRow.getSkill().getId()).isEqualTo(secondRow.getSkill().getId());
    }

    // --- 10-11: match_status enum ---

    @Test
    void everyValidMatchStatusValueRoundTripsCorrectly() {
        JobMatch jobMatch = newJobMatch("10");
        Skill matchedSkill = persistSkill("Docker", "docker");
        Skill partialSkill = persistSkill("Terraform", "terraform");
        Skill missingSkill = persistSkill("Ansible", "ansible");

        UUID matchedId = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, matchedSkill, MatchStatus.MATCHED, null)).getId();
        UUID partialId = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, partialSkill, MatchStatus.PARTIAL, null)).getId();
        UUID missingId = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, missingSkill, MatchStatus.MISSING, null)).getId();

        entityManager.clear();
        assertThat(entityManager.find(JobMatchSkill.class, matchedId).getMatchStatus())
                .isEqualTo(MatchStatus.MATCHED);
        assertThat(entityManager.find(JobMatchSkill.class, partialId).getMatchStatus())
                .isEqualTo(MatchStatus.PARTIAL);
        assertThat(entityManager.find(JobMatchSkill.class, missingId).getMatchStatus())
                .isEqualTo(MatchStatus.MISSING);
    }

    @Test
    void anInvalidMatchStatusIsRejectedByPostgresWithoutWeakeningTheJavaEnumMapping() {
        JobMatch jobMatch = newJobMatch("11");
        Skill skill = persistSkill("AWS", "aws");

        // Bypasses the JobMatchSkill entity entirely via a native insert,
        // to prove PostgreSQL's chk_job_match_skill_status CHECK
        // constraint itself rejects an invalid raw status string -
        // independent of the fact that MatchStatus's Java enum type
        // already makes constructing an invalid JobMatchSkill impossible
        // at compile time. Nothing about the Java mapping is loosened to
        // make this test possible.
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO job_match_skill (job_match_id, skill_id, match_status)
                        VALUES (:jobMatchId, :skillId, 'NOT_A_REAL_STATUS')
                        """)
                .setParameter("jobMatchId", jobMatch.getId())
                .setParameter("skillId", skill.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    // --- 12-13: identity-field immutability. Mutating the in-memory
    // field is just Java - these prove Hibernate excludes the columns
    // from its UPDATE statement, so the persisted values are unaffected. ---

    @Test
    void jobMatchIdCannotBeChangedThroughJpa() {
        JobMatch originalJobMatch = newJobMatch("12a");
        JobMatch otherJobMatch = newJobMatch("12b");
        Skill skill = persistSkill("Scala", "scala");
        JobMatchSkill jobMatchSkill = entityManager.persistAndFlush(
                new JobMatchSkill(originalJobMatch, skill, MatchStatus.MATCHED, null));
        UUID id = jobMatchSkill.getId();

        ReflectionTestUtils.setField(jobMatchSkill, "jobMatch", otherJobMatch);
        entityManager.persistAndFlush(jobMatchSkill);
        entityManager.clear();

        assertThat(entityManager.find(JobMatchSkill.class, id).getJobMatch().getId())
                .isEqualTo(originalJobMatch.getId());
    }

    @Test
    void skillIdCannotBeChangedThroughJpa() {
        JobMatch jobMatch = newJobMatch("13");
        Skill originalSkill = persistSkill("Kotlin", "kotlin");
        Skill otherSkill = persistSkill("Swift", "swift");
        JobMatchSkill jobMatchSkill = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, originalSkill, MatchStatus.MATCHED, null));
        UUID id = jobMatchSkill.getId();

        ReflectionTestUtils.setField(jobMatchSkill, "skill", otherSkill);
        entityManager.persistAndFlush(jobMatchSkill);
        entityManager.clear();

        assertThat(entityManager.find(JobMatchSkill.class, id).getSkill().getId())
                .isEqualTo(originalSkill.getId());
    }

    // --- 14-15: mutable result fields. JobMatchSkill has no setters by
    // design, so - consistent with how every other entity's mutability
    // was verified - these use ReflectionTestUtils to mutate the private
    // fields directly, proving Hibernate's dirty-checking honors the JPA
    // "updatable" mapping, independent of whether any service-level
    // reconciliation logic exists yet (it doesn't). ---

    @Test
    void matchStatusCanBeRecalculatedOnTheSameAssociation() {
        JobMatch jobMatch = newJobMatch("14");
        Skill skill = persistSkill("GraphQL", "graphql");
        JobMatchSkill jobMatchSkill = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, skill, MatchStatus.MISSING, null));
        UUID id = jobMatchSkill.getId();

        ReflectionTestUtils.setField(jobMatchSkill, "matchStatus", MatchStatus.MATCHED);
        entityManager.persistAndFlush(jobMatchSkill);
        entityManager.clear();

        JobMatchSkill reloaded = entityManager.find(JobMatchSkill.class, id);
        assertThat(reloaded.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        // Still the same row - recalculation updates in place, no new row.
        assertThat(reloaded.getId()).isEqualTo(id);
    }

    @Test
    void weightCanBeUpdatedOnTheSameAssociation() {
        JobMatch jobMatch = newJobMatch("15");
        Skill skill = persistSkill("Redis", "redis");
        JobMatchSkill jobMatchSkill = entityManager.persistAndFlush(
                new JobMatchSkill(jobMatch, skill, MatchStatus.PARTIAL, new BigDecimal("5.00")));
        UUID id = jobMatchSkill.getId();

        ReflectionTestUtils.setField(jobMatchSkill, "weight", new BigDecimal("12.50"));
        entityManager.persistAndFlush(jobMatchSkill);
        entityManager.clear();

        JobMatchSkill reloaded = entityManager.find(JobMatchSkill.class, id);
        assertThat(reloaded.getWeight()).isEqualByComparingTo("12.50");
    }

    // --- 16: nullable fields behave exactly as V1 defines ---

    @Test
    void nullableFieldsBehaveAccordingToTheSchema() {
        // weight is the only nullable column besides the DB-generated
        // created_at; id, job_match_id, skill_id, and match_status are
        // all NOT NULL in V1.
        JobMatch jobMatch = newJobMatch("16");
        Skill skill = persistSkill("Elasticsearch", "elasticsearch");
        JobMatchSkill minimal = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED, null);
        UUID id = entityManager.persistAndFlush(minimal).getId();

        entityManager.clear();
        JobMatchSkill reloaded = entityManager.find(JobMatchSkill.class, id);

        assertThat(reloaded.getWeight()).isNull();
        // Required fields are still present:
        assertThat(reloaded.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
    }

    // --- 17: database-generated fields ---

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        JobMatch jobMatch = newJobMatch("17");
        Skill skill = persistSkill("MongoDB", "mongodb");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED, null);

        JobMatchSkill persisted = entityManager.persistAndFlush(jobMatchSkill);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    // --- 18: NUMERIC(5,2) precision - unchanged by V5, independent of
    // the weight >= 0 guardrail ---

    @Test
    void aWeightValueExceedingNumericFivePrecisionTwoIsRejectedByPostgres() {
        // NUMERIC(5,2) allows at most 3 digits before the decimal point
        // (5 total precision - 2 scale). This constraint is unrelated to
        // and unchanged by V5's weight >= 0 guardrail below - both are
        // enforced, independently, by PostgreSQL.
        JobMatch jobMatch = newJobMatch("18");
        Skill skill = persistSkill("Cassandra", "cassandra");
        JobMatchSkill tooLarge = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED,
                new BigDecimal("1000.00"));

        assertThatThrownBy(() -> entityManager.persistAndFlush(tooLarge))
                .isInstanceOf(PersistenceException.class);
    }

    // --- 19-24: weight >= 0 guardrail added by
    // V5__constrain_job_match_skill_weight.sql. weight is a relative
    // skill-importance coefficient, not a percentage - so unlike
    // JobMatch's own scores, only a lower bound (>= 0) is enforced; there
    // is deliberately no domain-semantic upper bound such as 1 or 100,
    // since weights are not required to sum to 1 or to 100 (see
    // docs/matching-design.md / JobMatchSkill's class Javadoc). The
    // column's ordinary NUMERIC(5,2) representational precision limit
    // still applies independently of that semantic decision - see test
    // 18 above and aLargerRelativeWeightSucceedsBecauseNoDomainSemanticUpperBoundIsEnforced
    // below. ---

    @Test
    void weightOfNullSucceeds() {
        JobMatch jobMatch = newJobMatch("19");
        Skill skill = persistSkill("Prometheus", "prometheus");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED, null);

        JobMatchSkill persisted = entityManager.persistAndFlush(jobMatchSkill);

        assertThat(persisted.getWeight()).isNull();
    }

    @Test
    void weightOfZeroSucceeds() {
        // 0.00 is valid: the skill contributes zero relative weight
        // while still being representable for explanation/detail.
        JobMatch jobMatch = newJobMatch("20");
        Skill skill = persistSkill("Grafana", "grafana");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED,
                new BigDecimal("0.00"));

        JobMatchSkill persisted = entityManager.persistAndFlush(jobMatchSkill);

        assertThat(persisted.getWeight()).isEqualByComparingTo("0.00");
    }

    @Test
    void aSmallPositiveWeightSucceeds() {
        JobMatch jobMatch = newJobMatch("21");
        Skill skill = persistSkill("Vault", "vault");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED,
                new BigDecimal("1.50"));

        JobMatchSkill persisted = entityManager.persistAndFlush(jobMatchSkill);

        assertThat(persisted.getWeight()).isEqualByComparingTo("1.50");
    }

    @Test
    void aLargerRelativeWeightSucceedsBecauseNoDomainSemanticUpperBoundIsEnforced() {
        // weight is a relative coefficient, not a percentage - its
        // magnitude only has meaning relative to other per-skill weights
        // interpreted by the same algorithm version, so a value like
        // 250.00 is legitimate and must not be rejected. There is no
        // domain-semantic upper bound (unlike JobMatch's own 0-100
        // scores) - 250.00 still fits within NUMERIC(5,2)'s ordinary
        // representational precision limit (at most 3 digits before the
        // decimal point), which is the one limit that does apply; see
        // aWeightValueExceedingNumericFivePrecisionTwoIsRejectedByPostgres
        // above for the value (1000.00) that crosses it.
        JobMatch jobMatch = newJobMatch("22");
        Skill skill = persistSkill("Consul", "consul");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED,
                new BigDecimal("250.00"));

        JobMatchSkill persisted = entityManager.persistAndFlush(jobMatchSkill);

        assertThat(persisted.getWeight()).isEqualByComparingTo("250.00");
    }

    @Test
    void aNegativeWeightFails() {
        JobMatch jobMatch = newJobMatch("23");
        Skill skill = persistSkill("Nomad", "nomad");
        JobMatchSkill jobMatchSkill = new JobMatchSkill(jobMatch, skill, MatchStatus.MATCHED,
                new BigDecimal("-0.01"));

        assertThatThrownBy(() -> entityManager.persistAndFlush(jobMatchSkill))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aNativeInsertWithANegativeWeightIsRejectedByPostgres() {
        // Bypasses the JobMatchSkill entity entirely via a native insert,
        // to prove PostgreSQL's chk_job_match_skill_weight_non_negative
        // CHECK constraint itself rejects a negative weight - independent
        // of the JPA mapping.
        JobMatch jobMatch = newJobMatch("24");
        Skill skill = persistSkill("Istio", "istio");

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO job_match_skill (job_match_id, skill_id, match_status, weight)
                        VALUES (:jobMatchId, :skillId, 'MATCHED', -10.00)
                        """)
                .setParameter("jobMatchId", jobMatch.getId())
                .setParameter("skillId", skill.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }
}
