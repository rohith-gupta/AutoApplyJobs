package com.autoapplyjobs.platform.company;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link Company} JPA mapping against the real PostgreSQL
 * schema created by {@code db/migration/V1__initial_schema.sql} - in
 * particular, that {@code normalized_name} and {@code domain} are
 * candidate matching signals, not uniqueness constraints.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as every other entity test in this
 * codebase.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CompanyEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsACompany() {
        Company company = new Company("Acme Corp", "acme corp", "acme.com", "https://acme.com");

        Company persisted = entityManager.persistAndFlush(company);

        assertThat(persisted.getId()).isNotNull();
    }

    @Test
    void everyMappedFieldRoundTripsCorrectly() {
        Company company = new Company("Acme Corp", "acme corp", "acme.com", "https://acme.com");
        UUID id = entityManager.persistAndFlush(company).getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        Company reloaded = entityManager.find(Company.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("Acme Corp");
        assertThat(reloaded.getNormalizedName()).isEqualTo("acme corp");
        assertThat(reloaded.getDomain()).isEqualTo("acme.com");
        assertThat(reloaded.getWebsiteUrl()).isEqualTo("https://acme.com");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void nullableFieldsBehaveAccordingToTheSchema() {
        // domain and website_url are both nullable; name/normalized_name
        // are the only required fields.
        Company minimal = new Company("Acme Corp", "acme corp", null, null);
        UUID id = entityManager.persistAndFlush(minimal).getId();

        entityManager.clear();
        Company reloaded = entityManager.find(Company.class, id);

        assertThat(reloaded.getDomain()).isNull();
        assertThat(reloaded.getWebsiteUrl()).isNull();
        assertThat(reloaded.getName()).isEqualTo("Acme Corp");
        assertThat(reloaded.getNormalizedName()).isEqualTo("acme corp");
    }

    @Test
    void twoCompaniesWithTheSameNormalizedNameSucceed() {
        // Proves normalized_name is a candidate signal, not a unique
        // constraint - two distinct company rows may share it.
        Company first = entityManager.persistAndFlush(
                new Company("Acme Corp", "acme corp", "acme.com", null));
        Company second = entityManager.persistAndFlush(
                new Company("Acme Corp (subsidiary)", "acme corp", "acme-subsidiary.com", null));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getNormalizedName()).isEqualTo(second.getNormalizedName());
    }

    @Test
    void twoCompaniesWithTheSameDomainSucceed() {
        // Proves domain is a candidate signal, not a unique constraint -
        // two distinct company rows may share it (e.g. duplicate entries
        // created from different sources before canonicalization merges
        // them - not implemented in this step).
        Company first = entityManager.persistAndFlush(
                new Company("Acme Corp", "acme corp", "acme.com", null));
        Company second = entityManager.persistAndFlush(
                new Company("ACME Corporation", "acme corporation", "acme.com", null));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getDomain()).isEqualTo(second.getDomain());
    }

    @Test
    void databaseGeneratedFieldsPopulateCorrectly() {
        Company company = new Company("Acme Corp", "acme corp", "acme.com", null);

        Company persisted = entityManager.persistAndFlush(company);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }
}
