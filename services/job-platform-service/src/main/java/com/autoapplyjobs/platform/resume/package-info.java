/**
 * Resume module.
 *
 * <p>Owns {@code resume}, {@code resume_profile}, {@code resume_skill},
 * {@code resume_experience}, and {@code resume_education}. Resume
 * versioning, parsing, and skill/experience/education extraction.
 * Publishes {@code resume.processed} and {@code resume.default.changed}.
 * Account identity and preferences belong to
 * {@link com.autoapplyjobs.platform.user} instead.
 *
 * <p>See {@code docs/domain-model.md} and {@code docs/database-design.md}.
 */
package com.autoapplyjobs.platform.resume;
