# Domain Model

This document describes entities at the conceptual/domain level (the shape
Java entities will follow). Physical PostgreSQL table and column names are
all snake_case and are documented in `docs/database-design.md`; where a
name differs from its entity (only `User` → `app_user`), that's called out
there.

## Entities

**User** — an account holder. Owns resumes and has personal relationships
to jobs (saved, passed, applied).

**Company** — the canonical employer record. Different sources often name
the same employer inconsistently; `Job` points to one normalized `Company`.
Canonicalization is a candidate-matching process (name + domain signals),
not a strict uniqueness guarantee — see `docs/deduplication-design.md`.

**RawJobPosting** — exactly what was received from an external source: the
raw payload, untouched. Immutable once stored. Owned by ingestion; never
modified by Job Processing.

**RawJobProcessing** — Job Processing's own record of what happened when a
raw posting was normalized/deduplicated (status, error, which `JobSource`
it resolved to). Separate from `RawJobPosting` so ingestion's data is never
touched by downstream processing.

**Job** — one canonical, deduplicated job opportunity. What the user
actually searches and browses. Belongs to one `Company`.

**JobSource** — connects a canonical `Job` to one external source it was
discovered on (e.g. LinkedIn, a company's Greenhouse page). A `Job` can
have many `JobSource` rows — one per distinct source.

**JobLocation** — one location a `Job` is offered in (a job can be
multi-location or remote-plus-office).

**Skill** — a canonical, normalized skill (e.g. "Python").

**SkillAlias** — an alternate term that resolves to a canonical `Skill`
(e.g. "K8s" → Kubernetes). Every `Skill` has exactly one self-alias, so all
skill-term resolution — from jobs, resumes, or user input — has a single
lookup path.

**JobSkill** — a skill required/associated with a `Job`, extracted during
normalization.

**Resume** — one version of a user's resume. Its content and identity
(`user`, version number, file, uploaded-at) are immutable after creation;
`isDefault` — which resume is the user's current default for matching — is
the one field that ever changes post-creation. Flipping the default does
not create a new version, and it never changes which resume version a past
`Application` references. A `User` can have several resume versions over
time; a brand-new user may have zero.

**ResumeProfile** — the structured summary parsed from a `Resume` (name,
headline, years of experience, summary). One per `Resume`.

**ResumeSkill** — a skill extracted from a `Resume`.

**ResumeExperience** — one work-history entry parsed from a `Resume`.

**ResumeEducation** — one education entry parsed from a `Resume`; feeds
education-based matching.

**UserJobPreference** — a user's match-relevant preferences (locations,
workplace type, employment type, salary, sponsorship). One per `User`,
intentionally simple for now.

**JobMatch** — a computed match between one `Resume` version, one `Job`,
and one matching-algorithm version — broken into an overall score plus
per-dimension sub-scores (skills, experience, title, responsibility,
education, location, preference).

**JobMatchSkill** — the skill-level breakdown behind a `JobMatch`: for each
relevant skill, whether it was `MATCHED`, `PARTIAL`, or `MISSING`.

**SavedJob** — a `User`'s bookmark on a `Job` ("interested, revisit
later"). May be removed outright.

**PassedJob** — a `User`'s dismissal of a `Job` ("not interested, don't
show again"). May be removed outright.

**Application** — records that a `User` applied to a `Job`, using a
specific `Resume` version, with the match score at that moment frozen in
place. A user may reapply to the same job later — there's no uniqueness
constraint preventing it. Applications are archived, never hard-deleted.

**ApplicationStatusHistory** — the permanent, append-only audit trail of
status changes on an `Application` (e.g. Applied → Screening → Interview →
Offer).

## Relationships

```
User ──1───* Resume ──1───1 ResumeProfile
  │            ├──1───* ResumeSkill >──*───1 Skill ──1───* SkillAlias
  │            ├──1───* ResumeExperience
  │            └──1───* ResumeEducation
  ├──1───1 UserJobPreference
  │
  ├──1───* SavedJob      *───1── Job
  ├──1───* PassedJob     *───1── Job
  └──1───* Application   *───1── Job          (reapplication allowed)
                │              *───1── Resume  (version used)
                │              *───0..1── JobMatch
                │              *───0..1── JobSource  (applied via)
                └──1───* ApplicationStatusHistory

Company ──1───* Job

Job ──1───* JobSource ──0..1───* RawJobProcessing ──*───1── RawJobPosting
Job ──1───* JobLocation
Job ──1───* JobSkill >──*───1 Skill
Job ──1───* JobMatch  *───1── Resume
JobMatch ──1───* JobMatchSkill >──*───1 Skill
```
