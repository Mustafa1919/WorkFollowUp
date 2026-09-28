# WorkFollowUp

*[Türkçe](README.md) | English*

A multi-tenant work/task tracking and analytics application. On top of board-based task
management, it bakes team practices directly into the product — meeting-less standup
summaries, data-driven retros, Monte Carlo forecasting, Aging WIP alerts, and ready-made
automation templates — without reaching for a separate plugin or integration.

## What's in it

- **Board and tasks** — sprint/backlog, subtasks, dependencies, tags, saved views
- **Time machine** — view the board exactly as it looked at any point in the past
- **Meeting-less standup** — yesterday/today/blocked summaries compiled automatically, no meeting needed
- **Data-driven retro** — compares the sprint's starting commitment against what actually closed
- **Analytics** — Cycle Time, Velocity/Throughput, Aging WIP (threshold based on the project's
  own p85, not a fixed number), Monte Carlo bootstrap forecasting
- **Automation templates** — ready-made rules like "PR merge → Done" or "all subtasks done →
  parent to Review", toggled per project
- **Meeting scheduling, reporting, notifications/inbox, global search**
- **GitHub webhook + Slack integration**, personal access tokens (for API automation)
- **In-app feedback** and product usage/error telemetry

The reasoning behind product decisions lives in the ADRs under `docs/adr/`; the architecture
overview is in `docs/ARCHITECTURE_AND_PHASES.md` and the phase-by-phase design docs
(`docs/PHASE_*`) — currently Turkish-only.

## Tech stack

- **Backend:** Java 21, Spring Boot 4 (Security 7), PostgreSQL 16 (tenant isolation via
  Row-Level Security), Kafka (Transactional Outbox Pattern), Redis (cache-aside + distributed lock)
- **Frontend:** React 19 + TypeScript, Vite, TanStack Query, Zustand, Tailwind CSS, real-time
  updates over STOMP
- **Architecture:** modular monolith + selective worker split-off (Analytics Service, Webhook
  Ingestion Service) — a deliberate middle ground, avoiding an early jump to full microservices

See `docs/ARCHITECTURE_AND_PHASES.md` for the detailed reasoning (Turkish).

## Quick start

To try this **on your own machine** you'll need Docker (for Postgres/Redis/Kafka), JDK 21,
and Node.js. For step-by-step setup, known environment pitfalls, and the full environment
variable list (Turkish, most detailed source):

**→ [`docs/KURULUM.md`](docs/KURULUM.md)**

Short version:

```bash
docker compose up -d                                    # Postgres + Redis + Kafka
./mvnw -Dspring-boot.run.profiles=migrate spring-boot:run   # migration (one-off)
./mvnw spring-boot:run                                   # backend → :8080

cd frontend
cp .env.example .env.local
npm install
npm run dev                                               # frontend → :5173
```

When you sign in and create your first workspace, a short guided tour walks you through
the main areas (replay it any time from Settings → Guided tour). To try the API directly:
`http://localhost:8080/swagger-ui.html` and [`docs/API_KULLANIM.md`](docs/API_KULLANIM.md)
(Turkish).

## Project status

A personal/learning project under active development — no shortcuts or scope-cutting; each
phase is carried through with a real implementation. Current progress and roadmap are kept
in the project owner's own notes; the most reliable source in the repo itself is the design
docs under `docs/` and the decisions recorded under `docs/adr/`.

A separate security review is planned before any public deployment —
[`docs/security/README.md`](docs/security/README.md) (Turkish).
