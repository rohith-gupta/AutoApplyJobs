package com.autoapplyjobs.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for job-platform-service.
 *
 * <p>Connects to PostgreSQL via {@code spring.datasource.*} properties in
 * {@code application.properties}, which are entirely environment-variable
 * driven (see {@code infrastructure/docker/.env.example}). Hibernate schema
 * generation is disabled ({@code spring.jpa.hibernate.ddl-auto=none}) —
 * Flyway migrations are the only intended source of schema changes, once
 * they exist.
 */
@SpringBootApplication
public class JobPlatformServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobPlatformServiceApplication.class, args);
    }

}
