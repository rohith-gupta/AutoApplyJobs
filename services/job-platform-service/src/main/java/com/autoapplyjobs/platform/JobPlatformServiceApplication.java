package com.autoapplyjobs.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Entry point for job-platform-service.
 *
 * <p><strong>Temporary:</strong> {@code DataSourceAutoConfiguration},
 * {@code HibernateJpaAutoConfiguration}, and {@code FlywayAutoConfiguration}
 * are excluded so the service can start without a running PostgreSQL
 * instance. The driver, JPA, and Flyway dependencies are already on the
 * classpath for the "PostgreSQL Docker" and "Flyway configuration"
 * milestones — remove this exclusion once a real datasource is configured,
 * per {@code CLAUDE.md}'s current milestone list. Not a substitute
 * database (no H2/SQLite is introduced) — the app simply has no
 * persistence wired up yet.
 */
@SpringBootApplication(
        exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class
        }
)
public class JobPlatformServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobPlatformServiceApplication.class, args);
    }

}
