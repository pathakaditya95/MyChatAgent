# meta-autoreply — Implementation Plan

Spring Boot service that auto-replies to Facebook and Instagram comments and sends automated DMs, driven by keyword rules stored in Postgres.

**Audience:** Claude Code, executing inside VS Code.
**Human owner:** completes Phase 0 and Phase 11 manually (Meta dashboard work).

---

## How to use this document

Execute phases **in order**. Each phase has:

- **Goal** — what exists when the phase is done
- **Tasks** — exact files and behaviour
- **Acceptance** — objective checks that must pass before moving on

**Rules for the executing agent:**

1. Do not skip ahead. Each phase compiles and passes its checks before the next starts.
2. Do not invent Graph API payload shapes. Phase 5 captures real payloads first; parsers are written against captured fixtures, not assumptions.
3. Do not hardcode secrets. Everything sensitive comes from environment variables.
4. Run `./mvnw -q verify` after every phase. Fix failures before continuing.
5. Prefer constructor injection. No field `@Autowired`.
6. Prefer `RestClient` over `RestTemplate` or `WebClient`.
7. If a library version in this doc is stale, use the current stable release and note the change in `NOTES.md`.
8. When something is ambiguous, write the simplest thing that satisfies Acceptance and add a `TODO(human):` comment. Do not silently guess at Meta semantics.

---

## Phase 0 — Human prerequisites (NOT for the agent)

The agent should read this section for context but must not attempt it.

1. Create a Meta app at developers.facebook.com — type **Business**.
2. Add products: **Messenger**, **Instagram**, **Webhooks**.
3. Convert the Instagram account to **Business** or **Creator** and link it to the Facebook Page.
4. In Business Manager, create a **System User**, assign the Page and Instagram account, and generate a token with `never expires` selected. Scopes: `pages_messaging`, `pages_manage_metadata`, `pages_read_engagement`, `pages_show_list`, `instagram_basic`, `instagram_manage_comments`, `instagram_manage_messages`.
5. Record these values:
   - App Secret
   - System User access token
   - Facebook Page ID
   - Instagram Business Account ID
6. Keep the app in **Development Mode**. Add yourself and any test accounts under **App Roles → Testers**. Advanced Access via App Review is only needed to serve people who have no role on the app (Phase 12).

**Local machine requirements:** JDK 21, Docker Desktop, `cloudflared`, VS Code with Extension Pack for Java.

---

## Phase 1 — Project scaffold

**Goal:** an empty Spring Boot app that starts and answers a health check.

### Tasks

Generate a Maven project at the repository root.

- Group `com.example`, artifact `meta-autoreply`, package `com.example.metaautoreply`
- Java 21, Spring Boot 3.5.x (or current stable 3.x)
- Packaging jar, Maven wrapper included

Dependencies in `pom.xml`:

```
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-validation
spring-boot-starter-actuator
org.postgresql:postgresql          (runtime)
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
io.github.resilience4j:resilience4j-spring-boot3
com.bucket4j:bucket4j-core
org.projectlombok:lombok           (optional; omit if you prefer records)
spring-boot-starter-test           (test)
org.testcontainers:postgresql      (test)
org.testcontainers:junit-jupiter   (test)
org.wiremock:wiremock-standalone   (test)
```

Create `src/main/resources/application.yml`:

```yaml
spring:
  application.name: meta-autoreply
  threads.virtual.enabled: true
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/metaautoreply}
    username: ${DB_USER:meta}
    password: ${DB_PASSWORD:meta}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
    properties.hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
server.port: ${PORT:8080}
management.endpoints.web.exposure.include: health,info
meta:
  app-secret: ${META_APP_SECRET}
  verify-token: ${META_VERIFY_TOKEN}
  access-token: ${META_ACCESS_TOKEN}
  page-id: ${META_PAGE_ID}
  ig-user-id: ${META_IG_USER_ID}
  graph-version: ${META_GRAPH_VERSION:v21.0}
  graph-base-url: https://graph.facebook.com
  dry-run: ${META_DRY_RUN:true}
autoreply:
  process-interval-ms: 2000
  dispatch-interval-ms: 1000
  max-send-attempts: 5
  sends-per-hour: 200
  jitter-ms: 1500
```

Create `.env.example` listing every variable above with placeholder values. Create `.gitignore` covering `target/`, `.env`, `*.log`, `.idea/`, `captured/`.

Create `CLAUDE.md` at the root capturing rules 1–8 from "How to use this document" plus the package layout, so future sessions inherit the conventions.

Create `NOTES.md` — an append-only log of deviations, version bumps, and surprises found in real Meta payloads.

### Acceptance

- `./mvnw -q verify` succeeds
- `./mvnw spring-boot:run` starts (it will fail on DB connection until Phase 2 — that is expected; note it and continue)

---

## Phase 2 — Infrastructure and schema

**Goal:** Postgres running in Docker, Flyway migrations applied, app starts clean.

### Tasks

**`docker-compose.yml`** at root:

- Service `postgres`: image `postgres:16-alpine`, env `POSTGRES_DB=metaautoreply`, `POSTGRES_USER=meta`, `POSTGRES_PASSWORD=meta`, port `5432:5432`, named volume `pgdata`, healthcheck using `pg_isready`.
- Service `cloudflared`: image `cloudflare/cloudflared:latest`, command `tunnel --no-autoupdate run`, env `TUNNEL_TOKEN=${TUNNEL_TOKEN}`, `network_mode: host`. Mark it `profiles: ["tunnel"]` so it stays off by default.

Do **not** containerise the app yet. Running it from VS Code keeps the debugger attached.

**`src/main/resources/db/migration/V1__initial_schema.sql`:**

```sql
create table inbound_event (
    id            bigserial primary key,
    event_key     text        not null unique,
    platform      text        not null,
    event_type    text        not null,
    payload       jsonb       not null,
    status        text        not null default 'NEW',
    attempts      int         not null default 0,
    last_error    text,
    received_at   timestamptz not null default now(),
    processed_at  timestamptz
);
create index idx_inbound_event_status on inbound_event (status, received_at);

create table contact (
    id                   bigserial primary key,
    platform             text not null,
    external_id          text not null,
    username             text,
    last_interaction_at  timestamptz,
    opted_out            boolean not null default false,
    created_at           timestamptz not null default now(),
    unique (platform, external_id)
);

create table keyword_rule (
    id            bigserial primary key,
    name          text    not null,
    platform      text    not null,
    trigger_type  text    not null,
    match_type    text    not null,
    keyword       text    not null,
    public_reply  text,
    dm_text       text,
    enabled       boolean not null default true,
    priority      int     not null default 100,
    created_at    timestamptz not null default now()
);
create index idx_keyword_rule_lookup on keyword_rule (enabled, platform, trigger_type, priority);

create table outbound_message (
    id               bigserial primary key,
    kind             text    not null,
    platform         text    not null,
    target_id        text    not null,
    body             text    not null,
    status           text    not null default 'PENDING',
    attempts         int     not null default 0,
    next_attempt_at  timestamptz not null default now(),
    last_error       text,
    provider_msg_id  text,
    source_event_id  bigint references inbound_event (id),
    created_at       timestamptz not null default now(),
    sent_at          timestamptz,
    unique (kind, target_id)
);
create index idx_outbound_dispatch on outbound_message (status, next_attempt_at);
```

The `unique (kind, target_id)` constraint enforces Meta's **one private reply per comment** rule at the database level. Never remove it.

**`V2__seed_rules.sql`** — insert two disabled example rules so the table is never empty:

```sql
insert into keyword_rule (name, platform, trigger_type, match_type, keyword, public_reply, dm_text, enabled, priority)
values
 ('IG price keyword', 'IG', 'COMMENT', 'CONTAINS', 'price',
  'Just sent you a DM with details!', 'Hi! Here are the details you asked for: https://example.com', false, 10),
 ('FB info keyword', 'FB', 'COMMENT', 'EXACT', 'info',
  'Check your inbox.', 'Thanks for commenting! Here is the info: https://example.com', false, 20);
```

### Acceptance

- `docker compose up -d postgres` becomes healthy
- `./mvnw spring-boot:run` starts, Flyway reports 2 migrations applied
- `curl localhost:8080/actuator/health` returns `{"status":"UP"}`
- `\d inbound_event` in psql shows the table

---

## Phase 3 — Domain model and repositories

**Goal:** JPA entities matching the schema exactly, with `ddl-auto: validate` passing.

### Tasks

Enums in `domain/enums/`: `Platform` (IG, FB), `TriggerType` (COMMENT, MESSAGE), `MatchType` (EXACT, CONTAINS, REGEX), `EventStatus` (NEW, PROCESSING, DONE, SKIPPED, FAILED), `OutboundKind` (PUBLIC_REPLY, PRIVATE_REPLY, DM), `OutboundStatus` (PENDING, SENDING, SENT, FAILED, ABANDONED).

Entities in `domain/`: `InboundEvent`, `Contact`, `KeywordRule`, `OutboundMessage`. Use `@Enumerated(EnumType.STRING)`. Map `payload jsonb` as `String` with `@JdbcTypeCode(SqlTypes.JSON)`.

Repositories in `repo/`, extending `JpaRepository`. Two native queries carry the concurrency guarantees:

```java
@Query(value = """
    select * from inbound_event
    where status = 'NEW'
    order by received_at
    limit :limit
    for update skip locked
    """, nativeQuery = true)
List<InboundEvent> lockBatch(@Param("limit") int limit);
```

```java
@Query(value = """
    select * from outbound_message
    where status = 'PENDING' and next_attempt_at <= now()
    order by next_attempt_at
    limit :limit
    for update skip locked
    """, nativeQuery = true)
List<OutboundMessage> lockBatch(@Param("limit") int limit);
```

Both must be called inside `@Transactional` or the row locks release immediately.

Add `existsByEventKey(String)` and `findByPlatformAndExternalId(Platform, String)`.

### Acceptance

- App starts with `ddl-auto: validate` and no schema mismatch errors
- A Testcontainers integration test saves and reads back one row of each entity

---

## Phase 4 — Webhook endpoint

**Goal:** Meta can verify the endpoint and deliver signed events that land in `inbound_event`.

This phase contains the single most common failure in Meta integrations. Read carefully.

### Tasks

**`security/SignatureVerifier.java`**

- `boolean verify(String rawBody, String signatureHeader)`
- Header is `X-Hub-Signature-256`, formatted `sha256=<hex>`
- Compute HMAC-SHA256 over the **exact raw body bytes** using the app secret as key
- Compare with `MessageDigest.isEqual` — constant time, never `String.equals`
- Return false on null or malformed header

**`web/WebhookController.java`**

`GET /webhook` — subscription verification:

```java
@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
public ResponseEntity<String> verify(
        @RequestParam("hub.mode") String mode,
        @RequestParam("hub.verify_token") String token,
        @RequestParam("hub.challenge") String challenge) {
    if ("subscribe".equals(mode) && props.verifyToken().equals(token)) {
        return ResponseEntity.ok(challenge);
    }
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
}
```

The challenge must be echoed as **plain text**, not JSON, or Meta will refuse to activate the subscription.

`POST /webhook` — event intake:

```java
@PostMapping
public ResponseEntity<Void> receive(
        @RequestBody String rawBody,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) { ... }
```

Taking `String` is deliberate. If you bind to a DTO, Jackson deserializes and re-serializes, the bytes change, and the signature will never match.

Handler contract:

1. Verify signature. On failure return **403** and log a warning. Do not process.
2. Hand the raw string to `WebhookIngestService.ingest(rawBody)`.
3. Return **200** immediately.
4. Wrap everything after verification in try/catch. **Always return 200 on internal errors.** A non-200 makes Meta retry and eventually disable the subscription.

Target under 200ms. Only signature check plus one insert happen here — no Graph API calls, no rule matching.

**`ingest/WebhookIngestService.java`**

For now: parse the JSON just enough to split `entry[]` into individual events, derive an `event_key`, and insert one `inbound_event` row per event with `status = NEW` and the full sub-payload in `payload`. On duplicate key violation, catch and drop — Meta redelivers.

`event_key` derivation:

- comment events → the comment id
- message events → the message `mid`
- anything else → `sha256(rawSubPayload)` and `event_type = UNKNOWN`

Do not write parsers for specific event shapes yet. That is Phase 5.

### Acceptance

- Unit test: known body plus known secret produces the expected signature; a tampered body fails
- `GET /webhook?hub.mode=subscribe&hub.verify_token=<token>&hub.challenge=xyz` returns exactly `xyz` with content-type `text/plain`
- Wrong verify token returns 403
- POST with a valid signature inserts a row; POST with an invalid signature returns 403 and inserts nothing
- POST of the same payload twice inserts exactly one row
- A POST that throws internally still returns 200

---

## Phase 5 — Capture real payloads

**Goal:** fixtures from real Meta traffic before any parser is written.

This phase is mostly human-driven. The agent builds the tooling.

### Tasks

Add `GET /debug/events?limit=50` returning recent `inbound_event` rows as pretty JSON. Guard it behind a `debug` Spring profile so it never runs in production.

Add a Maven or shell task that dumps distinct `payload` samples grouped by `event_type` into `src/test/resources/fixtures/`.

**Then stop and instruct the human to:**

1. Start the tunnel: `cloudflared tunnel run` (or `docker compose --profile tunnel up -d`)
2. In the Meta dashboard, set the callback URL to `https://<hostname>/webhook` and the verify token
3. Subscribe the Page to `feed` and `messages`; subscribe Instagram to `comments` and `messages`
4. From a **tester account**, produce one of each: a Facebook comment, an Instagram comment, a Facebook DM, an Instagram DM, an Instagram story reply
5. Report back the captured payloads

### Acceptance

- At least one real captured payload per event type saved as a fixture
- `NOTES.md` records the actual JSON path to: comment id, commenter id, commenter username, comment text, parent media/post id, and the `verb` field
- Confirmed: does Instagram arrive with `object: "instagram"` or `object: "page"`? Record the answer.

**Do not proceed to Phase 6 until fixtures exist.** Writing parsers against guessed shapes is the main way this project fails.

---

## Phase 6 — Normalization and rule matching

**Goal:** raw events become a canonical shape, and matching rules produce queued outbound messages.

### Tasks

**`engine/NormalizedEvent.java`** — a record:

```java
public record NormalizedEvent(
    Platform platform,
    TriggerType triggerType,
    String eventKey,       // comment id or message mid
    String senderId,        // commenter PSID / IGSID
    String senderUsername,  // nullable
    String text,            // nullable
    String parentMediaId,   // nullable
    boolean fromSelf
) {}
```

**`ingest/payload/EventNormalizer.java`** — one method per platform, written strictly against the Phase 5 fixtures. Returns `Optional.empty()` for events that are not actionable.

Filter out, returning empty:

- `verb` other than `add` (edits and removes)
- events where `senderId` equals the configured Page ID or IG User ID — **your own replies**, which otherwise cause an infinite loop
- comments with no text

**`engine/RuleMatcher.java`**

- `Optional<KeywordRule> match(NormalizedEvent event)`
- Load enabled rules for the event's platform and trigger type, ordered by `priority` ascending
- Return the first match: `EXACT` is case-insensitive full-string equality on trimmed text; `CONTAINS` is case-insensitive substring; `REGEX` uses `Pattern.CASE_INSENSITIVE`
- Compile regex patterns once and cache them. Wrap matching in a timeout guard or reject patterns with nested quantifiers — a bad regex in a user-editable rule is a denial-of-service on your own scheduler.

**`engine/EventProcessor.java`**

```java
@Scheduled(fixedDelayString = "${autoreply.process-interval-ms}")
@Transactional
public void processBatch() { ... }
```

Per event:

1. `lockBatch(25)`
2. Normalize. Empty → mark `SKIPPED`, continue.
3. Upsert `Contact`; set `last_interaction_at = now()`. If `opted_out` → mark `SKIPPED`.
4. Match a rule. No match → `SKIPPED`.
5. If `rule.publicReply` is non-null, insert an `outbound_message` with kind `PUBLIC_REPLY`, `target_id = comment id`.
6. If `rule.dmText` is non-null, insert one with kind `PRIVATE_REPLY`, `target_id = comment id`.
7. Catch the unique-constraint violation on `(kind, target_id)` and treat it as success — it means this comment was already handled.
8. Mark the event `DONE` with `processed_at = now()`.

On unexpected exception: increment `attempts`, store `last_error`, and mark `FAILED` once attempts exceed 3.

Enable scheduling with `@EnableScheduling` on a `config/SchedulingConfig.java`.

### Acceptance

- Unit tests for all three match types, including case-insensitivity
- Test: an event whose sender is the Page ID produces no outbound rows
- Test: the same comment processed twice produces exactly one `PRIVATE_REPLY` row
- Integration test: insert a fixture event + enabled rule, run one scheduler tick, assert two `PENDING` outbound rows exist

---

## Phase 7 — Graph API client

**Goal:** a typed client for the four calls this service needs.

### Tasks

**`config/RestClientConfig.java`** — a `RestClient` bean with base URL from config, 5s connect / 10s read timeouts, and a request interceptor that logs method, URL, and status (never the token).

**`delivery/GraphApiClient.java`** — four methods, each returning the provider message id:

| Method | Endpoint | Body |
|---|---|---|
| `replyToIgComment(commentId, text)` | `POST /{version}/{commentId}/replies` | `message` |
| `replyToFbComment(commentId, text)` | `POST /{version}/{commentId}/comments` | `message` |
| `privateReply(platform, commentId, text)` | `POST /{version}/{igUserId or pageId}/messages` | `{"recipient":{"comment_id":"..."},"message":{"text":"..."}}` |
| `sendDm(platform, psid, text)` | `POST /{version}/{igUserId or pageId}/messages` | `{"recipient":{"id":"..."},"message":{"text":"..."}}` |

Access token goes in the `Authorization: Bearer <token>` header, **not** a query parameter — query strings end up in logs and proxies.

**`delivery/MetaErrorClassifier.java`**

Meta returns HTTP 400 for many conditions that are not client bugs. Parse `error.code` and `error.error_subcode` and classify:

- `RETRYABLE` — HTTP 5xx, codes 1, 2 (transient), 4, 17, 32, 613 (rate limits)
- `FATAL` — 100 (invalid parameter, includes deprecated message tags), 10, 200 (permission), 551 (user unavailable)
- `REAUTH` — 102, 190 (token expired or invalid)

Log the full error body on FATAL. Add an entry to `NOTES.md` for any unrecognised code encountered in practice.

**Resilience4j** — annotate the client with `@Retry(name = "graphApi")` and `@CircuitBreaker(name = "graphApi")`. Configure in `application.yml`: 3 attempts, exponential backoff from 1s, multiplier 2, retry only on `RetryableMetaException`.

**Dry-run mode.** When `meta.dry-run=true`, log the exact request that would be sent and return a synthetic id. This must be the default so no accidental live sends happen during development.

### Acceptance

- WireMock tests for all four calls asserting the exact URL, headers, and JSON body
- Test: a 400 with code 190 raises `ReauthMetaException` and is **not** retried
- Test: a 500 is retried 3 times then throws
- Test: with `dry-run=true`, zero HTTP calls are made

---

## Phase 8 — Outbound dispatcher

**Goal:** queued messages are sent, paced, retried, and never duplicated.

### Tasks

**`delivery/SendRateLimiter.java`** — Bucket4j, one bucket per platform, capacity `autoreply.sends-per-hour` refilled greedily over one hour. `boolean tryConsume()`, non-blocking. Meta's documented ceiling for private replies to post and Reel comments is 750/hour, but the anti-spam heuristics are the real constraint — 200/hour with jitter stays comfortably clear.

**`delivery/OutboundDispatcher.java`**

```java
@Scheduled(fixedDelayString = "${autoreply.dispatch-interval-ms}")
@Transactional
public void dispatchBatch() { ... }
```

Per message:

1. `lockBatch(10)`
2. `rateLimiter.tryConsume()` fails → push `next_attempt_at` forward 60s, leave `PENDING`, break out of the loop
3. Sleep a random 0–`jitter-ms` before sending (harmless on virtual threads)
4. Route on `kind` and `platform` to the right `GraphApiClient` method
5. Success → `status = SENT`, `sent_at = now()`, store `provider_msg_id`
6. `RETRYABLE` → increment `attempts`, `next_attempt_at = now() + 2^attempts minutes`, stay `PENDING`. At `max-send-attempts`, set `ABANDONED`.
7. `FATAL` → `status = FAILED`, store `last_error`, no retry
8. `REAUTH` → `status = FAILED`, log at ERROR with a clear "token needs regeneration" message

**Ordering matters:** when both a `PUBLIC_REPLY` and a `PRIVATE_REPLY` exist for the same comment, send the public reply first. Order the batch query by `kind` so `PUBLIC_REPLY` sorts ahead.

### Acceptance

- Integration test: 3 pending messages, WireMock returns 200, all become `SENT`
- Test: a 500 response moves the row back to `PENDING` with `next_attempt_at` in the future and `attempts = 1`
- Test: `attempts = max` plus another failure yields `ABANDONED`
- Test: with the bucket exhausted, no HTTP call is made
- Test: two dispatcher threads running concurrently never send the same row twice (`SKIP LOCKED` proof)

---

## Phase 9 — Rule admin API

**Goal:** manage keyword rules without touching SQL.

### Tasks

**`web/RuleAdminController.java`** — REST CRUD at `/api/rules`: list, get, create, update, delete, plus `PATCH /api/rules/{id}/toggle`.

Validation on the request DTO: `keyword` not blank; at least one of `publicReply` or `dmText` present; if `matchType = REGEX`, the pattern must compile — return 400 with a clear message if not.

Protect the whole `/api` path with HTTP Basic via `spring-boot-starter-security`, credentials from `ADMIN_USER` / `ADMIN_PASSWORD`. Explicitly permit `/webhook/**` and `/actuator/health` — Meta cannot authenticate.

Optionally add a single static `src/main/resources/static/admin.html` — a plain table plus a form calling the API. No framework, no build step.

### Acceptance

- Full CRUD works via `curl` with Basic auth
- Unauthenticated `/api/rules` returns 401
- Unauthenticated `POST /webhook` still works
- An invalid regex is rejected with 400

---

## Phase 10 — Test hardening

**Goal:** the suite catches regressions without a live Meta connection.

### Tasks

- `AbstractIntegrationTest` with a singleton Postgres Testcontainer reused across classes
- One end-to-end test: POST a real fixture payload with a valid signature → run both schedulers manually → assert WireMock received the expected Graph API calls
- Add Jacoco; target 70% line coverage on `engine` and `delivery`
- A README section documenting how to run everything locally

### Acceptance

- `./mvnw verify` passes from a clean clone with only Docker running
- No test requires network access to Meta

---

## Phase 11 — Go live (human + agent)

### Agent tasks

- Add a `Dockerfile` (multi-stage: Maven build, then `eclipse-temurin:21-jre-alpine`)
- Add the `app` service to `docker-compose.yml`, depending on healthy Postgres
- Add a `/actuator/health` readiness gate

### Human tasks

1. Set `META_DRY_RUN=false`
2. Enable one rule with a distinctive keyword
3. Comment that keyword from a tester account on a real post
4. Verify: public reply appears, DM arrives, `outbound_message` shows `SENT`
5. Watch for 24 hours; check for `FAILED` rows

### Acceptance

- One real end-to-end auto-reply and auto-DM confirmed on both platforms
- Restarting the app mid-flight loses nothing (rows resume from `PENDING`)

---

## Phase 12 — App Review (human only)

Development Mode restricts the bot to people holding a role on the app. To serve a real audience, submit for Advanced Access on `pages_messaging` and `instagram_manage_messages`.

Prepare: a privacy policy URL, a data deletion callback endpoint, a screencast of the flow working, and a written use-case description. Meta removed the screen-recording upload requirement in May 2026 and now surfaces approval criteria directly in the App Dashboard under Permissions & Features — check there for the current list before submitting. Business Verification is likely required as well.

Add a `GET /privacy` and `POST /data-deletion` endpoint to satisfy the review requirements.

---

## Compliance invariants — never remove

1. **One private reply per comment.** Enforced by `unique (kind, target_id)`.
2. **7-day private reply window.** A comment older than 7 days cannot receive a private reply — check before queueing.
3. **24-hour messaging window.** Any DM not triggered by a comment requires an interaction in the last 24 hours. Check `contact.last_interaction_at`.
4. **No self-replies.** Filter events where the sender is your own Page or IG account.
5. **Honour opt-out.** A message containing `stop` or `unsubscribe` sets `contact.opted_out = true` and suppresses all future sends.
6. **Pace sends.** Never disable the rate limiter.

Violating these risks the Facebook Page and Instagram account, not just the service.

---

## Reference: final structure

```
meta-autoreply/
├── CLAUDE.md
├── NOTES.md
├── README.md
├── docker-compose.yml
├── Dockerfile
├── .env.example
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/metaautoreply/
    │   │   ├── MetaAutoReplyApplication.java
    │   │   ├── config/       AppProperties, MetaProperties, RestClientConfig,
    │   │   │                 SchedulingConfig, SecurityConfig
    │   │   ├── security/     SignatureVerifier
    │   │   ├── web/          WebhookController, RuleAdminController, DebugController, dto/
    │   │   ├── domain/       InboundEvent, Contact, KeywordRule, OutboundMessage, enums/
    │   │   ├── repo/         four repositories
    │   │   ├── ingest/       WebhookIngestService, payload/EventNormalizer
    │   │   ├── engine/       EventProcessor, RuleMatcher, NormalizedEvent
    │   │   └── delivery/     OutboundDispatcher, GraphApiClient, SendRateLimiter,
    │   │                     MetaErrorClassifier, exceptions/
    │   └── resources/
    │       ├── application.yml
    │       ├── db/migration/ V1__initial_schema.sql, V2__seed_rules.sql
    │       └── static/admin.html
    └── test/
        ├── java/...
        └── resources/fixtures/
```

---

## Suggested execution prompt

> Read `PLAN.md` in full, then execute Phase 1. Stop when Phase 1 Acceptance passes and report status. Do not begin Phase 2 until I confirm.

Driving one phase per session keeps context tight and gives you a review point before each layer builds on the last.
