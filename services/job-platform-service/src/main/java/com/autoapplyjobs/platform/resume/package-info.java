/**
 * Resume module.
 *
 * <p>Reserved for resume-specific logic (parsing, versioning, education/
 * experience/skill extraction). {@code docs/architecture.md} currently
 * describes table ownership as a single combined "User/Resume" module
 * ({@code app_user}, {@code resume} and related tables,
 * {@code user_job_preference}); whether resume logic lives here or under
 * {@link com.autoapplyjobs.platform.user} is not yet decided and should be
 * settled before entities are added to either package.
 */
package com.autoapplyjobs.platform.resume;
