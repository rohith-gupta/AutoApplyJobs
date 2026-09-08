/**
 * Search module.
 *
 * <p>Read-side search/filter over canonical jobs, exposed through the REST
 * API. Reads across owned tables from other modules rather than owning any
 * tables itself. OpenSearch is explicitly out of scope for now — search is
 * backed by PostgreSQL queries until that changes (see
 * {@code docs/roadmap.md}).
 */
package com.autoapplyjobs.platform.search;
