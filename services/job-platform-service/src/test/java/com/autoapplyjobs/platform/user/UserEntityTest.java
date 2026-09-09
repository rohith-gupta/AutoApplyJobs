package com.autoapplyjobs.platform.user;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@link User} JPA mapping against the real PostgreSQL schema
 * created by {@code db/migration/V1__initial_schema.sql}.
 *
 * <p>{@code replace = Replace.NONE} is required here: {@code @DataJpaTest}
 * substitutes an embedded database by default, and this project never uses
 * H2/SQLite - the real Docker Postgres instance (see
 * infrastructure/docker/) is used instead, the same way
 * {@code JobPlatformServiceApplicationTests} does.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistAssignsDatabaseGeneratedIdAndTimestamps() {
        User user = new User("mapping-test-1@example.com", "hash", "Mapping Test");

        User persisted = entityManager.persistAndFlush(user);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    @Test
    void roundTripsCorrectlyThroughARealReadAfterClearingThePersistenceContext() {
        User user = new User("mapping-test-2@example.com", "hash", "Round Trip");
        UUID id = entityManager.persistAndFlush(user).getId();

        // Force a real SELECT against PostgreSQL rather than returning the
        // same in-memory managed instance.
        entityManager.clear();
        User reloaded = entityManager.find(User.class, id);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getEmail()).isEqualTo("mapping-test-2@example.com");
        assertThat(reloaded.getPasswordHash()).isEqualTo("hash");
        assertThat(reloaded.getFullName()).isEqualTo("Round Trip");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void emailUniquenessIsCaseInsensitiveBecauseTheColumnIsCitext() {
        entityManager.persistAndFlush(new User("CaseTest@Example.com", "hash", "First"));

        // Same address, different case - only enforced as a duplicate
        // because app_user.email is CITEXT, not a plain unique TEXT column.
        // This is the real behavior of the database column, not something
        // reproducible by testing plain-String uniqueness alone.
        assertThatThrownBy(() ->
                entityManager.persistAndFlush(new User("casetest@example.com", "hash", "Second"))
        ).isInstanceOf(PersistenceException.class);
    }
}
