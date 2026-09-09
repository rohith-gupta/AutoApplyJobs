# infrastructure/docker

Local PostgreSQL only, via Docker Compose. No Kafka, OpenSearch, Redis,
pgAdmin, application schema SQL, or Flyway migrations here — see
`../../docs/roadmap.md` for sequencing.

## Setup

```
cp .env.example .env
```

Edit `.env` if needed. It's gitignored — never commit it. `POSTGRES_PORT`
defaults to `5432` in `docker-compose.yml`, but if that port is already in
use (a native PostgreSQL install is common), set `POSTGRES_PORT=5433` (or
another free port) in `.env` instead.

## Start / stop

```
docker compose up -d          # start (detached)
docker compose ps             # check health status
docker compose logs -f postgres
docker compose down           # stop, keep data
docker compose down -v        # stop and delete the data volume
```

Run these from this directory (`infrastructure/docker/`), or add `-f
infrastructure/docker/docker-compose.yml` from elsewhere.

## What this provides

- PostgreSQL 17 (pinned image tag, `postgres:17.11-alpine`), reachable at
  `localhost:${POSTGRES_PORT}`. Pinned to 17, not 18+, because the Flyway
  version Spring Boot 3.5.16 manages doesn't yet officially support
  PostgreSQL 18 (18 reports a compatibility warning on connect). Revisit
  once Spring Boot manages a Flyway version with certified 18 support.
- Database `autoapplyjobs`, application user `autoapplyjobs` — both from
  `.env`, never hard-coded in `docker-compose.yml`
- Data persisted in the named volume `autoapplyjobs_postgres_data`
  (survives `docker compose down`; removed only by `down -v`)
- A `pg_isready` healthcheck

No schema exists in this database yet — that's the Flyway migration
milestone, not this one.
