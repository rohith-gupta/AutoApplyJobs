/**
 * Applications module.
 *
 * <p>Owns {@code saved_job}, {@code passed_job}, {@code application}, and
 * {@code application_status_history}. {@code application} is archived, never
 * hard-deleted; {@code saved_job} / {@code passed_job} may be hard-deleted
 * freely.
 *
 * <p>See {@code docs/database-design.md}.
 */
package com.autoapplyjobs.platform.application;
