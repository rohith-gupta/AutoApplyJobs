/**
 * Matching module.
 *
 * <p>Owns {@code job_match} and {@code job_match_skill}. Scores a resume
 * version against a job for a given matching algorithm version, triggered
 * by {@code job.canonical.created}, {@code job.canonical.updated},
 * {@code resume.processed}, and {@code resume.default.changed}.
 *
 * <p>See {@code docs/matching-design.md}.
 */
package com.autoapplyjobs.platform.matching;
