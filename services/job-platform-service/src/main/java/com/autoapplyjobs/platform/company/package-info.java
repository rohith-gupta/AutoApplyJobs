/**
 * Company module, part of Job Processing.
 *
 * <p>Owns {@code company}. Employer canonicalization is candidate-matched
 * (name + domain signals), never a strict uniqueness guarantee — see
 * {@code docs/deduplication-design.md}.
 */
package com.autoapplyjobs.platform.company;
