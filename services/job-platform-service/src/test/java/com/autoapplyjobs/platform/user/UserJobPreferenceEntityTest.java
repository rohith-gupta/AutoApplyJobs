package com.autoapplyjobs.platform.user;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link UserJobPreference} JPA mapping - including its
 * shared-primary-key relationship to {@link User} - against the real
 * PostgreSQL schema created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE}: no H2/SQLite, the real Docker
 * PostgreSQL instance is used, same as {@code UserEntityTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserJobPreferenceEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    private User persistTestUser(String email) {
        return entityManager.persistAndFlush(new User(email, "hash", "Test User"));
    }

    @Test
    void persistsForAnExistingUser() {
        User user = persistTestUser("pref-test-1@example.com");
        UserJobPreference preference = new UserJobPreference(user);
        preference.setSponsorshipRequired(true);
        preference.setSalaryMinPreference(new BigDecimal("120000.00"));
        preference.setSalaryCurrency("USD");

        UserJobPreference persisted = entityManager.persistAndFlush(preference);

        assertThat(persisted.getUserId()).isEqualTo(user.getId());
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    @Test
    void reloadsCorrectlyIncludingArrayAndNumericFields() {
        User user = persistTestUser("pref-test-2@example.com");
        UserJobPreference preference = new UserJobPreference(user);
        preference.setPreferredLocations(new String[] {"Remote", "Austin"});
        preference.setWorkplaceTypePreference(new String[] {"REMOTE", "HYBRID"});
        preference.setEmploymentTypePreference(new String[] {"FULL_TIME"});
        preference.setSalaryMinPreference(new BigDecimal("95000.50"));
        preference.setSalaryCurrency("USD");
        preference.setSponsorshipRequired(false);
        entityManager.persistAndFlush(preference);
        UUID id = user.getId();

        // Force a real SELECT against PostgreSQL.
        entityManager.clear();
        UserJobPreference reloaded = entityManager.find(UserJobPreference.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getPreferredLocations()).containsExactly("Remote", "Austin");
        assertThat(reloaded.getWorkplaceTypePreference()).containsExactly("REMOTE", "HYBRID");
        assertThat(reloaded.getEmploymentTypePreference()).containsExactly("FULL_TIME");
        assertThat(reloaded.getSalaryMinPreference()).isEqualByComparingTo("95000.50");
        assertThat(reloaded.getSalaryCurrency()).isEqualTo("USD");
        assertThat(reloaded.getSponsorshipRequired()).isFalse();
    }

    @Test
    void userRelationshipResolvesCorrectly() {
        User user = persistTestUser("pref-test-3@example.com");
        entityManager.persistAndFlush(new UserJobPreference(user));
        UUID id = user.getId();

        entityManager.clear();
        UserJobPreference reloaded = entityManager.find(UserJobPreference.class, id);

        assertThat(reloaded.getUser()).isNotNull();
        assertThat(reloaded.getUser().getId()).isEqualTo(id);
        assertThat(reloaded.getUser().getEmail()).isEqualTo("pref-test-3@example.com");
    }

    @Test
    void violatesForeignKeyWithoutAValidUser() {
        // A proxy for a User id that was never persisted - no DB hit yet,
        // so this only fails when the FK is actually checked on flush.
        User nonExistentUser = entityManager.getEntityManager()
                .getReference(User.class, UUID.randomUUID());
        UserJobPreference orphan = new UserJobPreference(nonExistentUser);

        assertThatThrownBy(() -> entityManager.persistAndFlush(orphan))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void enforcesOnePreferenceRowPerUserViaThePrimaryKey() {
        User user = persistTestUser("pref-test-5@example.com");
        entityManager.persistAndFlush(new UserJobPreference(user));

        // Same user -> same derived id -> primary key collision.
        UserJobPreference duplicate = new UserJobPreference(user);

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(PersistenceException.class);
    }
}
