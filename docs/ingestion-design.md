# Ingestion Design

Owned by `job-ingestion-service`. Its only job is to pull postings from
permitted external sources and store them exactly as received — nothing
downstream of that belongs here.

## Responsibilities

- Run one connector per source (e.g. a specific job board or ATS API)
- Fetch postings on a schedule
- Write each distinct payload to `raw_job_posting`, unmodified
- Publish an event so Job Processing knows there's new work

## What ingestion does *not* do

- Normalize fields
- Decide whether a posting is a duplicate
- Create or update `job`, `company`, `job_source`, or any canonical data
- Track its own processing/retry state — that's `raw_job_processing`, owned
  by Job Processing, in a separate table

This separation exists so ingestion's data is trustworthy raw history:
whatever Job Processing's normalizer does, right or wrong, `raw_job_posting`
still holds exactly what the source returned and can be reprocessed later.

## Idempotent ingestion

Re-running a connector against a source that hasn't changed must not create
duplicate rows. `raw_job_posting` enforces `UNIQUE(source_name, source_url,
content_hash)`:

- Same URL, same content → same row (insert is a no-op / upsert)
- Same URL, changed content (e.g. a description edit) → a **new** row,
  because `raw_job_posting` snapshots are immutable — history of what
  changed is preserved rather than overwritten

## Connector pattern

Each source gets its own connector responsible for:

1. Fetching data via whatever means that source supports (API, scrape, feed)
2. Producing a normalized *envelope* around the raw payload: `source_name`,
   `source_native_id` (if the source provides one), `source_url`,
   `raw_payload`, `content_hash`, `fetched_at`
3. Writing it to `raw_job_posting`

Connectors are independent of each other — one source being slow, flaky, or
rate-limited never blocks another.

## Triggering

- **Local dev:** an interval loop or manual trigger runs each connector in
  turn; no queue needed.
- **Cloud (later):** managed cron triggers each connector as its own
  scalable unit.

## Event published

`raw_job_posting.ingested` — tells Job Processing there's a new (or
updated) raw posting to normalize. Locally this can be a direct call or a
row picked up by polling; on Kafka it's a real topic. The payload should
carry enough to look the row up (`raw_job_posting.id`) — Job Processing
reads the row itself rather than trusting a duplicated payload in the
event.

## Permitted sources only

Every connector must only pull from sources the project has permission to
use (public APIs within their terms of service, or explicitly authorized
partnerships) — no scraping that violates a source's terms.
