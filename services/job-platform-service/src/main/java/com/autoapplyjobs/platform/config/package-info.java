/**
 * Config module.
 *
 * <p>Application-level Spring configuration (bean wiring, cross-cutting
 * setup). Currently empty — the one piece of temporary startup
 * configuration this milestone needed (disabling datasource/JPA/Flyway
 * auto-configuration until PostgreSQL is available) lives directly on
 * {@link com.autoapplyjobs.platform.JobPlatformServiceApplication} instead,
 * so its temporary nature is visible where the application boots.
 */
package com.autoapplyjobs.platform.config;
