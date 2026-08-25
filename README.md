# meta-autoreply

Spring Boot service that auto-replies to Facebook and Instagram comments and
sends automated DMs, driven by keyword rules stored in Postgres.

- **[PLAN.md](PLAN.md)** — the phased implementation plan and the source of truth for scope.
- **[CLAUDE.md](CLAUDE.md)** — conventions, package layout, and the compliance invariants.
- **[NOTES.md](NOTES.md)** — running log of deviations, version bumps, and real Meta payload quirks.

## Status

Phase 8 complete — the pipeline runs end to end. Signed webhook intake lands in
`inbound_event`; a scheduled processor normalises events, matches keyword rules
and queues outbound messages; a paced dispatcher drains the queue through a typed
Graph API client with retry, circuit breaking and error classification.

Phase 9 adds a rule admin API behind HTTP Basic; Phase 10 hardens the test suite
(145 tests, one shared Postgres container, coverage gated on `engine` and
`delivery`).

**Nothing is sent for real yet.** `META_DRY_RUN` defaults to `true`, which logs
each request and returns a synthetic id. Phase 11 is the deliberate switch to
live traffic.

## Managing rules

`/api/rules` is protected by HTTP Basic using `ADMIN_USER` / `ADMIN_PASSWORD`.
`/webhook/**` and `/actuator/health` stay open — Meta cannot authenticate, and a
webhook behind a login gets retried and then disabled.

```bash
curl -u "$ADMIN_USER:$ADMIN_PASSWORD" localhost:8080/api/rules
```

```bash
curl -u "$ADMIN_USER:$ADMIN_PASSWORD" -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{"name":"price","platform":"IG","triggerType":"COMMENT","matchType":"CONTAINS","keyword":"price","publicReply":"Just sent you a DM!","dmText":"Here are the details.","priority":10}'
```

Endpoints: `GET /api/rules`, `GET /api/rules/{id}`, `POST /api/rules`,
`PUT /api/rules/{id}`, `PATCH /api/rules/{id}/toggle`, `DELETE /api/rules/{id}`.

There is also a dependency-free page at `/admin.html` (same credentials) that
lists rules and lets you create, enable and delete them.

Note that in zsh, `curl $ARGS` does not word-split — pass `-u "$USER:$PASSWORD"`
directly rather than via a variable holding the whole flag.

## Capturing real payloads (Phase 5)

Run the app under the `debug` profile — VS Code config
**meta-autoreply (app, debug capture)**, or:

```bash
set -a && source .env && set +a && SPRING_PROFILES_ACTIVE=debug ./mvnw spring-boot:run
```

Expose it and point the Meta webhook at `https://<tunnel-host>/webhook`:

```bash
cloudflared tunnel --url http://localhost:8080
```

Watch what arrives, then write fixtures:

```bash
curl -s localhost:8080/debug/events/types
```

```bash
./scripts/dump-fixtures.sh
```

`/debug/*` exposes raw payloads including commenter ids and message text, so it
exists only under the `debug` profile. Never enable that profile in production.

The app refuses to start unless every `META_*` variable is set — see
[Configuration](#configuration).

Note: the test suite requires a running Docker daemon from Phase 3 onward —
`DomainPersistenceTest` boots a Postgres container via Testcontainers.

## Prerequisites

- JDK 21
- Docker Desktop
- `cloudflared` (from Phase 5)

## Run locally

Start the database, then the app:

```bash
docker compose up -d postgres
```

```bash
./mvnw spring-boot:run
```

`curl localhost:8080/actuator/health` should return `"status":"UP"`.

## Running in VS Code

`.vscode/launch.json` defines a **meta-autoreply (app)** configuration that loads
`.env` via the Java debugger's `envFile` setting. Start Postgres first, then hit
F5 (or pick the config in the Run and Debug panel). Breakpoints work normally.

`.vscode/` is gitignored, so the config stays local. To share it, remove
`.vscode/` from `.gitignore` — `launch.json` references `.env` by path and
contains no secrets itself.

## Running the whole stack in Docker

Local development runs the app from VS Code against a containerised Postgres. To
run everything in Docker instead:

```bash
docker compose --profile app up -d --build
```

The `app` profile keeps this off by default, so `docker compose up -d postgres`
stays the development path with the debugger attached. The container reads the
same `.env`, overriding only `DB_URL` to reach `postgres` by service name.

Readiness is gated on the database, and that is what the container healthcheck
uses:

```bash
curl -s localhost:8080/actuator/health/readiness
```

`/actuator/health/liveness` stays UP while the database is unreachable —
deliberately, so an orchestrator stops sending traffic without restarting a
process that is merely waiting.

## Configuration

Spring Boot does not read `.env` files by itself. VS Code handles it through
`envFile`; from a terminal, export the variables first:

```bash
set -a && source .env && set +a && ./mvnw spring-boot:run
```

Every `META_*` variable is required. If one is missing the app exits at startup
with a message naming it, rather than running with an unresolved placeholder as
its secret — see the Phase 4 security entry in [NOTES.md](NOTES.md).

Build and test:

```bash
./mvnw -q verify
```

Always use `./mvnw`, not `mvn` — see [NOTES.md](NOTES.md) for why.

## Testing

`./mvnw verify` runs everything from a clean clone. The only requirement is a
running Docker daemon; no test touches the network beyond localhost, and none can
reach Meta.

- **Postgres** comes from a single Testcontainers instance shared by every test
  class. Starting one container instead of one per Spring context is most of the
  reason the suite takes about a minute.
- **The Graph API** is stubbed with WireMock on a random localhost port.
- **Live sends are impossible.** `AbstractIntegrationTest` pins
  `meta.dry-run=true` as an inline `@SpringBootTest` property, which outranks any
  `META_DRY_RUN` exported in your shell. The three classes that need real HTTP
  point `meta.graph-base-url` at their own WireMock. `HermeticConfigTest` asserts
  all of this, so it cannot regress silently.

Coverage is enforced at 70% of lines on `engine` and `delivery` — the packages
that decide what to send and then send it. The report lands in
`target/site/jacoco/index.html`.

```bash
open target/site/jacoco/index.html
```

Useful subsets while working:

```bash
./mvnw test -Dtest=EndToEndFlowTest
```

`EndToEndFlowTest` is the one to read first: it posts a real captured payload
with a valid signature and asserts the exact Graph API calls that come out.

Copy `.env.example` to `.env` and fill it in before running. `META_DRY_RUN`
defaults to `true`; leave it that way until Phase 11 so no live messages are
sent during development.

Full local-run documentation lands in Phase 10.
