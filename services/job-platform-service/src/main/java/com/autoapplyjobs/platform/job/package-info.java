/**
 * Job Processing module.
 *
 * <p>Owns {@code job}, {@code job_source}, {@code job_location},
 * {@code skill}, {@code skill_alias}, {@code job_skill}, and
 * {@code raw_job_processing}. Normalizes and deduplicates raw postings
 * (owned by {@code job-ingestion-service}) into canonical jobs. Publishes
 * {@code job.canonical.created} / {@code job.canonical.updated}.
 *
 * <p>See {@code docs/deduplication-design.md}.
 */
package com.autoapplyjobs.platform.job;
