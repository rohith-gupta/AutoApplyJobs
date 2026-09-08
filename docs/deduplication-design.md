# Deduplication Design

Owned by Job Processing, inside `job-platform-service`. Runs when a
`raw_job_posting` is normalized, deciding whether it belongs to an existing
`job` or should become a new one.

## Principle

Two postings can look identical on the surface (same company, same title,
same city) and still be genuinely different openings — a large company can
have several openings with the same title in the same location at once. So
`job.dedup_fingerprint` (company + normalized title + location) is
deliberately **not** a unique constraint; it is a candidate signal, not
proof of identity.

## Signal hierarchy (strongest first)

1. **Company + requisition ID** — `job(company_id, requisition_id)` is
   the one dedup signal enforced as a real DB uniqueness constraint
   (partial, `WHERE requisition_id IS NOT NULL`). When a source provides a
   requisition/req ID, this is treated as ground truth.

2. **External/ATS identity already seen** — `job_source(source_name,
   source_native_id)` (or `source_url` when no native ID exists) is itself
   unique. If a raw posting's source + native ID already resolves to a
   `job_source` row, reuse that row's `job_id` directly rather than
   re-evaluating anything else — this is what makes re-ingesting the same
   posting from the same source idempotent.

3. **Canonical apply URL** — `job.canonical_apply_url_hash` is a strong
   signal (the same apply link showing up from two different sources is
   very likely the same job) but is indexed, not unique, since URL
   normalization can be imperfect (tracking parameters, redirects,
   mirrored listings). Treat a match here as a strong candidate requiring
   the normal confirmation step below, not an automatic merge.

4. **Fingerprint match** — `job.dedup_fingerprint` (normalized company +
   title + location) is a candidate lookup only. A fingerprint match is a
   starting point for comparison, never a merge decision by itself.

5. **Fuzzy / description similarity** — last resort for near-duplicates
   that don't fingerprint-match exactly (reworded titles, differently
   formatted locations). Confidence below a threshold should route to a
   manual review path rather than auto-merge, to avoid silently merging
   two distinct jobs.

## Flow

```
raw_job_posting
    │
    ▼
normalize fields (title, normalized_title, location, company, skills, salary, requisition_id, canonical_apply_url, ...)
    │
    ▼
resolve company  (candidate match on company.normalized_name / company.domain, or create new)
    │
    ▼
check dedup signals in order (1 → 5 above)
    │
    ├── strong match found ──▶ attach new job_source to the existing job
    │                          (or update job_source.last_seen_at if this
    │                           exact source+identity was already linked)
    │
    └── no confident match ──▶ create new job + job_source
    │
    ▼
raw_job_processing row updated: processing_status = PROCESSED,
job_source_id set, processed_at set
    │
    ▼
publish job.canonical.created / job.canonical.updated
```

## Company canonicalization

Same shape of problem as job dedup, one level up: `company.normalized_name`
and `company.domain` are both indexed candidate signals, neither unique.
Resolving a company means matching on those signals with the same
strongest-signal-first posture — domain match is stronger evidence than a
normalized-name match — falling back to creating a new `company` row when
nothing matches with confidence.

## Idempotency

Reprocessing the same `raw_job_posting` with the same `normalizer_version`
must be a no-op that lands on the same result — enforced by
`raw_job_processing`'s unique `(raw_job_posting_id, normalizer_version)`.
Reprocessing with a *new* normalizer version is expected to happen
occasionally (an improved normalizer/dedup pass run against history) and
produces a new `raw_job_processing` row, potentially re-linking the same
raw posting to a different (better-matched) canonical `job`.
