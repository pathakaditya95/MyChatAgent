# NOTES.md

Append-only log of deviations from `PLAN.md`, version bumps, and surprises
found in real Meta payloads. Newest entries at the bottom of each section.

## Deviations and version bumps

### 2026-08-14 — Phase 1: toolchain installed, Acceptance verified

The scaffold was originally written on a machine with no JDK, Maven, Docker, or
Homebrew, so it was committed unverified. The owner then installed JDK 21,
Homebrew, Maven, and Docker, and Phase 1 Acceptance was run and **passed**:

- `./mvnw -q verify` → `BUILD SUCCESS`, 3/3 tests green.
- `./mvnw spring-boot:run` → starts on Java 21, Tomcat binds 8080, then fails at
  `flywayInitializer` with `Connection to localhost:5432 refused`. This is the
  failure `PLAN.md` predicts for Phase 1 and is resolved by Phase 2. Nothing
  else failed — notably there were no `Could not resolve placeholder` errors,
  confirming the unset `META_*` variables stay inert until something binds them.

### 2026-08-14 — Toolchain gotcha: always use `./mvnw`, never `mvn`

Homebrew installed both `maven` and `openjdk` 26. The two entry points resolve
different JDKs on this machine:

| Command  | JDK resolved                          |
|----------|---------------------------------------|
| `./mvnw` | 21.0.11 (`/Library/Java/JavaVirtualMachines/jdk-21.jdk`) — correct |
| `mvn`    | 26.0.2 (Homebrew Cellar) — wrong      |

`JAVA_HOME` is unset; the wrapper picks up JDK 21 from `PATH` while the Homebrew
`mvn` launcher finds its own. **Always build with `./mvnw`.** If you must use
`mvn`, export `JAVA_HOME=$(/usr/libexec/java_home -v 21)` first. This is also why
`PLAN.md` rule 4 says `./mvnw -q verify` rather than `mvn verify`.

### 2026-08-14 — Phase 1: Spring Boot 4.1.0 instead of 3.5.x

`PLAN.md` specifies Spring Boot 3.5.x. Spring Initializr no longer offers any
3.x line — the current default is 4.1.0 and the oldest offered release is 4.0.7.
Per plan rule 7, the project targets **Spring Boot 4.1.0** on **Java 21**.

Boot 4 renamed several artifacts the plan lists by their Boot 3 names. The
mapping actually used:

| `PLAN.md` (Boot 3)                     | Used (Boot 4)                            |
|----------------------------------------|------------------------------------------|
| `spring-boot-starter-web`              | `spring-boot-starter-webmvc`             |
| `org.flywaydb:flyway-core`             | `spring-boot-starter-flyway`             |
| `spring-boot-starter-test`             | Per-starter `-test` artifacts: `spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-validation-test`, `-actuator-test`, `-flyway-test` |
| `org.testcontainers:postgresql`        | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:junit-jupiter`     | `org.testcontainers:testcontainers-junit-jupiter` |
| `io.github.resilience4j:resilience4j-spring-boot3` | `io.github.resilience4j:resilience4j-spring-boot4` 2.4.0 |

`resilience4j-spring-boot4` 2.4.0 was verified against Maven Central to depend on
`spring-boot-autoconfigure` 4.0.0, confirming it is the Boot 4 build.

**Gotcha:** Spring Initializr reports its boot version id as `4.1.0.RELEASE` and
writes that string into the generated `pom.xml`, but no such artifact exists on
Maven Central — the published coordinate is plain `4.1.0`. The `.RELEASE` suffix
is an Initializr-internal id. Corrected in `pom.xml`; if you regenerate from
Initializr, strip the suffix again.

Testcontainers arrives via `testcontainers-bom` **2.0.5** (the 2.x line), which
is what renamed the artifacts and moved the container classes.
`org.testcontainers.containers.PostgreSQLContainer` still exists as a deprecated
alias, but the new `org.testcontainers.postgresql.PostgreSQLContainer` is used.

`org.flywaydb:flyway-database-postgresql` is still required explicitly — Boot 4
does not pull the Postgres dialect in transitively.

Testcontainers also moved `PostgreSQLContainer` from
`org.testcontainers.containers` to `org.testcontainers.postgresql`.

Versions pinned as `pom.xml` properties (not managed by the Boot BOM):
`resilience4j 2.4.0`, `bucket4j-core 8.10.1`, `wiremock-standalone 3.13.2`.
WireMock's `org.wiremock:wiremock-standalone` latest is `4.0.0-beta.38`; the
newest **stable** release, 3.13.2, was chosen instead.

### 2026-08-14 — Phase 1: deviations from the plan's file list

- **`spring-boot-starter-security` deliberately omitted.** `PLAN.md` introduces
  it in Phase 9. Adding it at Phase 1 would put HTTP Basic in front of
  everything and break the Phase 2 acceptance check
  (`curl localhost:8080/actuator/health`).
- **Phase 1 test suite is Docker-free.** `ApplicationConfigTest` asserts that
  `application.yml` parses and declares every key the later phases read, and
  that `meta.dry-run` still defaults to `true`. It deliberately does not refresh
  the Spring context, because a context refresh opens a JDBC connection and
  there is no database until Phase 2. `TestcontainersConfiguration` is present
  but unused until Phase 3; its image is pinned to `postgres:16-alpine` to match
  the `docker-compose.yml` that Phase 2 will add.
- **`PLAN.md` committed to the repository.** `CLAUDE.md` cites it as the source
  of truth, so it needs to live in the repo rather than in chat history.

## Open items

- [x] ~~Verify Phase 1 with `./mvnw -q verify`.~~ Done 2026-08-14, passing.
- [x] ~~Install Docker Desktop before starting Phase 2.~~ Docker 29.7.2 installed.
- [ ] Install `cloudflared` before starting Phase 5.

### 2026-08-14 — Phase 2: infrastructure and schema, Acceptance verified

All four Acceptance checks passed:

- `docker compose up -d postgres` → healthy on the first probe (Postgres 16.15).
- `./mvnw spring-boot:run` → Flyway reported `Successfully applied 2 migrations
  to schema "public", now at version v2`.
- `curl localhost:8080/actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`.
  The `groups` key is standard Boot output when liveness/readiness probes are
  available; `status` is `UP` as the plan requires.
- `\d inbound_event` → matches `V1__initial_schema.sql` exactly.

Additionally verified beyond the plan's checks:

- `unique (kind, target_id)` exists on `outbound_message` as
  `outbound_message_kind_target_id_key`, and was **actively proven** by inserting
  a duplicate `(PRIVATE_REPLY, comment_123)` inside a transaction — Postgres
  rejected it with a unique-violation, and the rollback left the table empty.
  This is compliance invariant #1 working end to end.
- `flyway_schema_history` shows both migrations with `success = t`.
- Both seed rules loaded with `enabled = f`, priorities 10 and 20.
- `ddl-auto: validate` passes against the new schema (there are no entities to
  validate until Phase 3, but the setting is active and did not fail startup).

### 2026-08-14 — Phase 2: deviations

- **`docker compose` warning suppressed.** `TUNNEL_TOKEN` is written as
  `${TUNNEL_TOKEN:-}` rather than the plan's `${TUNNEL_TOKEN}`, so compose does
  not warn about an unset variable on every command while the `tunnel` profile
  is inactive. Behaviour is otherwise identical.
- **`network_mode: host` is a known Phase 5 risk on macOS.** Docker Desktop for
  Mac does not give containers the host network stack the way Linux does, so the
  `cloudflared` container may be unable to reach the app on `localhost:8080`.
  Flagged with a `TODO(human):` in `docker-compose.yml`; the fallback is to run
  `cloudflared` natively (`brew install cloudflared`) or target
  `host.docker.internal:8080`. Not resolved now because Phase 5 is where it is
  actually exercised.
- **`version:` key omitted** from `docker-compose.yml` — obsolete in Compose v2
  and warns if present.

### 2026-08-14 — Running the app in a background shell

`./mvnw spring-boot:run &` from a wrapper shell gets killed when that shell
exits, part-way through startup. In this session the app died right after Flyway
finished but before Tomcat bound its port, which looks alarming in the log but
is not a defect. Run it in the foreground, or expect a second launch to fail
with `Port 8080 was already in use` if an earlier orphan is still holding it.

### 2026-08-14 — Phase 3: domain model and repositories, Acceptance verified

Both Acceptance checks passed:

- `./mvnw spring-boot:run` starts with `ddl-auto: validate` and no schema
  mismatch. Flyway reports `Schema "public" is up to date. No migration
  necessary.`, Tomcat binds 8080, health is `UP`.
- `DomainPersistenceTest` saves and reads back one row of every entity against a
  Testcontainers Postgres. `./mvnw verify` → 11/11 green.

**`validate` was proven active, not assumed.** Passing tests only show that
nothing is wrong *if* validation is switched on, so a bogus
`@Column(name = "column_that_does_not_exist")` was temporarily added to
`Contact`. Hibernate failed startup with
`SchemaManagementException: Schema validation: missing column
[column_that_does_not_exist] in table [contact]`. The field was then reverted.
Entity/schema drift will fail the build from here on.

### 2026-08-14 — Phase 3: mapping decisions

- **Timestamps are `java.time.Instant`**, mapping to `timestamptz`. Combined with
  `hibernate.jdbc.time_zone: UTC` from `application.yml`, everything is stored
  and compared in UTC. Validation confirms the column types line up.
- **Column defaults are duplicated as Java field initialisers** (`status = NEW`,
  `attempts = 0`, `enabled = true`, `priority = 100`, `optedOut = false`,
  `receivedAt/createdAt/nextAttemptAt = Instant.now()`). JPA inserts every column
  explicitly, so relying on the SQL `default` clause alone would write nulls. The
  two definitions must be kept in step — if a default changes in a migration,
  change the entity too.
- **`event_type` is a `String`, not an enum.** `PLAN.md` Phase 4 requires storing
  `UNKNOWN` for unrecognised shapes, so the column has to accept values the code
  does not yet know about.
- **`OutboundMessage.sourceEvent` is a lazy `@ManyToOne`**, not a bare `Long`.
  Phase 6 already holds the `InboundEvent` it is processing, so assigning the
  reference is natural. `open-in-view: false` means it must be touched inside the
  transaction that loaded it.
- **`KeywordRuleRepository` is intentionally empty** apart from the `JpaRepository`
  base methods, carrying a `TODO(human):` for the ordered lookup. That query
  belongs to Phase 6; adding it now would be skipping ahead.

### 2026-08-14 — Phase 3: tests now require Docker

Phase 1's suite was deliberately Docker-free. `DomainPersistenceTest` ends that:
it is a `@SpringBootTest` importing `TestcontainersConfiguration`, so
`./mvnw verify` now needs a running Docker daemon. Test startup is ~15-20s for
the container.

Two extra tests were added beyond the plan's "save and read back one row"
requirement, covering the native `for update skip locked` queries — one asserting
a due row is claimable, one asserting a row whose `next_attempt_at` is in the
future is skipped. These queries are the concurrency backbone of Phases 6 and 8,
and a malformed one would otherwise surface inside a scheduler, where it is much
harder to see. The tests prove the SQL parses and filters correctly; they do
**not** prove the locking semantics under concurrency — `PLAN.md` schedules that
proof for Phase 8.

### 2026-08-14 — Phase 4: webhook endpoint, Acceptance verified

All six Acceptance checks passed, verified over real HTTP against a running app
(not only through MockMvc):

| Check | Result |
|---|---|
| Known body + secret produces expected signature; tampered body fails | Pass |
| `GET /webhook?...&hub.challenge=xyz` returns exactly `xyz` | `200`, body `xyz`, `Content-Type: text/plain;charset=UTF-8` |
| Wrong verify token | `403` |
| POST valid signature inserts a row | `200`, one row keyed `live_comment_1` |
| POST invalid signature returns 403 and inserts nothing | `403`, zero rows |
| Same payload posted twice inserts exactly one row | `200` twice, one row |
| POST that throws internally still returns 200 | Pass (ingest mocked to throw) |

`./mvnw verify` → 39/39 green.

### 2026-08-14 — Phase 4: SECURITY — unresolved placeholders bound as literals

**Found and fixed during this phase.** `application.yml` declares
`meta.app-secret: ${META_APP_SECRET}` with no default, on the theory that a
missing secret would fail startup. It does not.

`@ConfigurationProperties` binding does **not** behave like `@Value`: when a
placeholder cannot be resolved, the Binder leaves the *literal text* in place
instead of throwing, and `@NotBlank` is satisfied by it. The app therefore
started cleanly with no `META_*` variables set and used the constant string
`${META_APP_SECRET}` as its HMAC key — a value published in this repository, so
anyone could have forged a valid webhook signature. This was confirmed live: a
`GET /webhook` handshake with `hub.verify_token=${META_VERIFY_TOKEN}` returned
`200` and echoed the challenge.

Fixed with a compact constructor in `MetaProperties` that rejects any required
value which is blank or is a whole-string `${...}` placeholder, naming the
missing environment variable in the message. Verified: `./mvnw spring-boot:run`
with no env now exits 1 with
`Configuration 'meta.app-secret' resolved to the literal placeholder
${META_APP_SECRET} ... Refusing to start rather than run with a publicly known
secret.` and never binds a port. `MetaPropertiesTest` locks the behaviour in.

**Rule for later phases:** any new required secret added to `MetaProperties`
must be added to the `requireResolved` list too. `@NotBlank` alone is not
sufficient protection.

### 2026-08-14 — Phase 4: implementation notes

- **Jackson 3, not Jackson 2.** Boot 4 ships `tools.jackson.core:jackson-databind`
  3.1.4, so imports are `tools.jackson.databind.*`, not
  `com.fasterxml.jackson.databind.*`. The node accessor is `asString(default)`;
  Jackson 2's `asText(default)` still exists but is the legacy spelling.
- **Ingest parses the envelope only.** It walks `entry[]`, then `changes[]` and
  `messaging[]`, and stores each element verbatim. Field semantics are left to
  Phase 6 per plan rule 2. Every guess about *where* an id lives carries a
  `TODO(human):` — comment id, message mid, and the `object` → platform mapping.
- **Nothing is silently dropped.** An entry with neither `changes[]` nor
  `messaging[]`, or a body with no `entry[]` at all, is stored whole as
  `UNKNOWN` keyed by a SHA-256 of the payload. Phase 5 mines exactly these rows
  for fixtures, so discarding them would hide what we most need to see.
- **Idempotency is a pre-check plus a caught violation.** `existsByEventKey`
  handles the common redelivery path; the `DataIntegrityViolationException`
  catch handles a lost race. Each `save` runs in its own transaction, so one
  collision does not roll back the rest of the batch.
- **Tests need a `test` profile.** `src/test/resources/application-test.yml`
  supplies dummy `meta.*` values, and every context-loading test carries
  `@ActiveProfiles("test")`. Without it the new `MetaProperties` guard fails the
  context refresh.

### 2026-08-14 — Gotcha: openssl signature helper

`openssl dgst -sha256 -hmac "$SECRET"` on this machine prints the bare hex with
no `(stdin)= ` prefix, so the common `awk '{print $2}'` idiom yields an empty
string and every request 403s. Use `awk '{print $NF}'`, which works either way.
Worth remembering when hand-testing the webhook.

### 2026-08-14/15 — Phase 5: COMPLETE

Agent-side tooling built and smoke-tested on 08-14; real traffic captured from
both platforms on 08-15. **Phase 5 Acceptance met** — fixtures exist for
Instagram comments and messages and Facebook comments and messages, and the JSON
paths are recorded under "Real Meta payload observations".

Getting Facebook flowing took two fixes, both detailed below: the Page had to be
subscribed to the app via `/{page-id}/subscribed_apps`, and the app turned out to
have two distinct webhook signing secrets.

Built:

- `GET /debug/events?limit=50` — recent rows, newest first, pretty-printed, with
  the stored payload spliced back in as real JSON rather than an escaped string
  so it can be copied straight into a fixture. `limit` is clamped to 1..500.
- `GET /debug/events/types` — counts per event type. This is the capture
  checklist: keep interacting until every type you need shows a non-zero count.
- Both are behind `@Profile("debug")`. That guard is a privacy control, not a
  convenience — the responses contain commenter ids, usernames and message text.
  `DebugControllerTest` asserts both that the endpoints work under the profile
  and that they 404 without it.
- `scripts/dump-fixtures.sh` — writes distinct payloads per `(platform, event_type)`
  into `src/test/resources/fixtures/`, pretty-printed. Refuses to overwrite an
  existing fixture unless `FORCE=1`, so a re-run cannot destroy a capture that
  took real effort to produce.
- VS Code launch config **meta-autoreply (app, debug capture)** sets
  `SPRING_PROFILES_ACTIVE=debug`.

Smoke-tested end to end with one synthetic signed event, then **the synthetic
fixture and its database row were deleted**. `src/test/resources/fixtures/` is
deliberately empty: a leftover fake fixture is exactly how Phase 6 gets written
against invented shapes, which `PLAN.md` names as the main way this project fails.

### 2026-08-15 — Phase 6: normalization and rule matching, Acceptance verified

`./mvnw verify` → 89/89. All four Acceptance checks pass, plus an end-to-end run
against the real captured events.

**Live run against the 15 real captured events** (all rules disabled, so nothing
should be queued):

| Platform | Type | Outcome |
|---|---|---|
| FB | FEED | 1 × no matching rule |
| FB | MESSAGE | 4 × no matching rule |
| IG | COMMENT | 4 × no matching rule |
| IG | MESSAGE | 3 × **not actionable** (echoes / attachment-only) |
| IG | MESSAGE | 3 × no matching rule |

Zero outbound rows queued, and two contacts discovered with the right display
field per platform — `Swati Shri Pal Singh` (FB `name`) and `science_hustler`
(IG `username`). The three "not actionable" rows are the echo filter working on
real traffic.

### FINDING: Meta echoes our own outgoing DMs back to us

Two of the three captured Instagram message fixtures have `"is_echo": true` with
`sender.id` equal to our own IG account — they are DMs *we* sent, delivered back
to us as webhook events.

Without a filter, the service would treat its own reply as a new inbound message,
match a rule, reply again, and loop indefinitely. This is compliance invariant #4
in its most dangerous form, and it is invisible until real traffic arrives —
which is precisely why the plan forbids writing parsers before Phase 5.

`EventNormalizer` drops any message with `message.is_echo == true`, *before* the
`fromSelf` id comparison. Both checks are kept deliberately: the echo flag is the
explicit signal and does not depend on `META_PAGE_ID` / `META_IG_USER_ID` being
configured correctly — and they were wrong (swapped) for part of this project.

### Phase 6: implementation notes

- **Regex denial-of-service guard.** `PLAN.md` asks for a timeout or a nested
  quantifier check. Implemented as a step budget: the subject is wrapped in a
  `CharSequence` that counts `charAt` calls and aborts past 200,000. The regex
  engine reads the subject through `charAt`, so counting reads bounds the work
  without needing a watchdog thread. Tested with `(a+)+$` against 40 a's plus a
  `!`, which would otherwise backtrack effectively forever.
- **`Locale.ROOT` for case folding.** `"INFO".toLowerCase()` is `"ınfo"` in a
  Turkish locale, which would silently stop `CONTAINS` rules matching. The
  default locale is never used for matching.
- **Opt-out uses word boundaries.** `\b(stop|unsubscribe)\b`, so "stopwatch" and
  "nonstop" do not opt someone out. There is a test for it.
- **Unique-constraint handling is a pre-check, not a caught exception.** Catching
  the violation would poison the batch transaction and roll back every other
  event in it. `existsByKindAndTargetId` keeps the common redelivery path cheap;
  the constraint is still the real guarantee, and a lost race simply rolls the
  batch back to be retried.
- **Schedulers are disabled under the `test` profile**
  (`autoreply.scheduling-enabled: false`, honoured by `SchedulingConfig`). A
  timer firing mid-assertion produces flakiness that is miserable to diagnose;
  tests call `processBatch()` directly.
- **`EventProcessorTest` is `@Transactional`.** It clears `keyword_rule` in
  `@BeforeEach`, and without rollback that committed deletion removed the V2 seed
  rules for the whole run — surfacing as a failure in `DomainPersistenceTest`,
  a different class, depending on execution order.

### FINDING: environment variables silently override the test profile

**Reported as a test failure, root-caused 2026-08-15.**
`EventProcessorTest.anEventFromOurOwnAccountProducesNoOutboundRows` failed on the
owner's machine while passing here — twice, and in isolation.

Cause: Spring Boot ranks **OS environment variables above profile-specific YAML**
packaged in the application. Sourcing `.env` into the shell — or running tests
from VS Code with `envFile` — replaced `application-test.yml`'s values with real
credentials. `meta.ig-user-id` bound to the real Instagram account, so the
self-reply check no longer recognised the test account's id, and an event that
should have been ignored produced two outbound rows.

Reproduced deterministically with `set -a && source .env && set +a && ./mvnw test`.

**Fix:** a shared `@IntegrationTest` meta-annotation pins every `meta.*` value as
inline `@SpringBootTest(properties = ...)`. Inline test properties rank *above*
environment variables, so they win. Applied to every context-loading test.

Two of the pinned values are safety guards rather than correctness ones:

- `meta.dry-run=true` — an exported `META_DRY_RUN=false` must never let the suite
  send live traffic to a real Page or Instagram account. From Phase 7 onward that
  is the difference between a test run and a production incident.
- `autoreply.scheduling-enabled=false` — tests drive one batch at a time.

`HermeticConfigTest` asserts the guarantee directly, and it was verified against a
deliberately hostile environment (`META_DRY_RUN=false` plus the real Page and IG
ids exported): 92/92 still green, with `dryRun()` bound to `true`.

**`application-test.yml` is now belt-and-braces only.** Anything that must hold
regardless of the developer's shell belongs in `@IntegrationTest`, not the YAML.

### Phase 6: deviations from PLAN.md

- **`NormalizedEvent` has a ninth component, `occurredAt`.** The plan lists
  eight. Compliance invariant #2 (7-day private reply window) cannot be checked
  without a timestamp, and the two platforms supply it differently
  (`created_time` in seconds vs nothing at all for Instagram comments, where
  `inbound_event.received_at` is used as the proxy).
- **`MESSAGE`-triggered rules queue an `OutboundKind.DM` targeted at the sender.**
  The plan's `EventProcessor` steps describe only the comment path, but
  `TriggerType.MESSAGE` and `OutboundKind.DM` both exist in the Phase 3 model, so
  leaving message rules inert would make them dead configuration. The 24-hour
  window (invariant #3) is satisfied by construction here — the contact has just
  messaged us. `TODO(human):` confirm this interpretation.
- **The 7-day window suppresses only the private reply**, not the public reply.
  Public comment replies have no such restriction.

### 2026-08-15 — Phase 7: Graph API client, Acceptance verified

`./mvnw verify` → 109/109. All four Acceptance checks pass:

| Check | Result |
|---|---|
| WireMock tests for all four calls, asserting URL, headers and JSON body | 13 tests |
| 400 with code 190 raises `ReauthMetaException` and is **not** retried | 1 HTTP call made |
| A 500 is retried 3 times then throws | 3 HTTP calls made |
| With `dry-run=true`, zero HTTP calls | verified against a live WireMock |

Also asserted: the access token never appears as a query parameter, and a
transient 500 that recovers on the second attempt succeeds without surfacing an
error.

### Phase 7: implementation notes

- **The two endpoints return the id under different keys.** Comment replies
  (`/{comment-id}/replies`, `/{comment-id}/comments`) answer `{"id": ...}`; the
  messaging endpoint answers `{"recipient_id": ..., "message_id": ...}`. The
  client takes the field name per call rather than guessing.
- **A success response with no id is still a success.** Meta has accepted the
  message; losing the id costs traceability, not delivery, so the client logs a
  warning and returns `"unknown"` rather than failing a message that was sent.
- **Unrecognised error codes are treated as FATAL, not retryable.** Retrying an
  error we do not understand risks hammering Meta and tripping the anti-spam
  heuristics — which costs the Page, not one message. The classifier logs such a
  code at ERROR asking for it to be recorded here and classified explicitly.
- **The circuit breaker only records `RetryableMetaException`.** A fatal
  rejection is a fact about one message, not about Graph API health; counting it
  would let a run of bad rules push the breaker open and stall every other send.
- **The request interceptor logs method, URL and status only.** Never headers
  (the bearer token) and never the body (the text being sent to a real person).

### Gotcha: WireMock and HTTP/2

`GraphApiClientTest` initially built its client with a bare
`RestClient.builder()`, whose default request factory negotiates HTTP/2. Against
WireMock that fails with `Received RST_STREAM: Stream cancelled`, which reads
like a client bug and is not. Building through the real `RestClientConfig` fixes
it and has the side benefit of putting the production timeouts, request factory
and interceptor under test.

### Phase 7: deviations from PLAN.md

- **`GraphApiRetryTest` cannot use `@IntegrationTest`.** That annotation pins
  `meta.dry-run=true` and a real `graph-base-url`, both of which would defeat a
  test whose entire purpose is real HTTP. It declares its own properties with
  `dry-run=false` and points `graph-base-url` at a local WireMock via
  `@DynamicPropertySource`, so nothing can reach a real account. This is the one
  place in the suite where dry-run is off, and it is deliberate and contained.

### 2026-08-15 — Phase 8: outbound dispatcher, Acceptance verified

`./mvnw verify` → 124/124. All five Acceptance checks pass:

| Check | Result |
|---|---|
| 3 pending messages, 200 responses, all become SENT | ✓ |
| A 500 returns the row to PENDING, `next_attempt_at` in the future, `attempts = 1` | ✓ |
| `attempts = max` plus another failure yields ABANDONED | ✓ |
| With the bucket exhausted, no HTTP call is made | ✓ |
| Two dispatcher threads never claim the same row (SKIP LOCKED proof) | ✓ |

**End-to-end run against the real captured events**, dry-run on: three genuine
Instagram comments produced three `PUBLIC_REPLY` plus three `PRIVATE_REPLY` rows,
all reaching SENT with synthetic ids. Log counts: 6 DRY RUN lines, **0 real Graph
API calls**. Demo rule and rows were removed afterwards; the database is back to
2 seed rules, both disabled.

### FINDING: `order by kind` sorts the wrong way

`PLAN.md` Phase 8 says "Order the batch query by `kind` so PUBLIC_REPLY sorts
ahead." Sorted as text it does the opposite — `PRIVATE_REPLY` < `PUBLIC_REPLY`
alphabetically — so a literal implementation would DM the commenter before
posting the visible reply.

`lockBatch` therefore uses an explicit ranking:

```sql
order by case kind when 'PUBLIC_REPLY' then 0 when 'PRIVATE_REPLY' then 1 else 2 end,
         next_attempt_at
```

### Phase 8: implementation notes

- **An open circuit breaker defers without counting an attempt.**
  `CallNotPermittedException` means Graph API is unhealthy, not that this message
  is bad. Counting it as a failure would be actively harmful: with a 30-second
  open state and a 1-second dispatch interval, a brief outage would burn through
  `max-send-attempts` and abandon perfectly good messages in well under a minute.
  Same treatment as a rate-limit refusal — push `next_attempt_at` out and stop
  the batch.
- **Rate-limit refusal breaks the batch rather than skipping the row.** The
  remaining rows are largely for the same platform and would fail the same
  check, so continuing would just spin through the batch doing database work for
  nothing.
- **Unexpected exceptions are treated as retryable.** A socket reset should not
  permanently fail a message; `max-send-attempts` still bounds it.
- **A FATAL or REAUTH failure does not increment `attempts`.** The count means
  "times we tried and might try again", and neither of those will be retried.

### Phase 8: deviations from PLAN.md

- **`SendRateLimiter.tryConsume` takes a `Platform`.** The plan writes
  `boolean tryConsume()` but also specifies one bucket per platform; the two
  cannot both be true, and the per-platform bucket is the substantive
  requirement.
- **`OutboundDispatcherTest` runs with `dry-run=false`** against a localhost
  WireMock, for the same reason as `GraphApiRetryTest`. Failure modes are driven
  by mocking `GraphApiClient`, which is the only practical way to produce a
  specific classified exception on demand.

### 2026-08-15 — Phase 9: rule admin API, Acceptance verified

`./mvnw verify` → 140/140, and every Acceptance check confirmed with `curl`
against a running app:

| Check | Result |
|---|---|
| Full CRUD via curl with Basic auth | create/get/list/update/toggle/delete all pass |
| Unauthenticated `/api/rules` | `401` |
| Unauthenticated `POST /webhook` still works | `403` — refused on *signature*, not auth |
| Invalid regex rejected | `400` with "keyword must be a valid regular expression when matchType is REGEX" |

That third row is the one worth reading carefully. A `403` there is the correct
result: the request reached the webhook handler and was rejected for having no
valid signature. A `401` would have meant security was intercepting it, which is
the failure mode that silently kills the integration.

### Phase 9: security decisions

- **`/debug/**` now requires authentication too.** `PLAN.md` only asks for `/api`
  to be protected, but the debug endpoints return raw payloads — commenter ids,
  usernames, message text. The `debug` profile already gates them, but while a
  Cloudflare tunnel is up the entire app is publicly reachable at a URL that is
  random rather than secret. Defence in depth for PII seemed clearly worth the
  deviation.
- **CSRF is disabled deliberately, not by omission.** This is a stateless API
  driven by curl and Basic auth, and `/webhook` must accept unauthenticated POSTs
  from Meta. CSRF protection assumes a browser session and a token, neither of
  which exists here.
- **`anyRequest().denyAll()`.** Anything not explicitly listed is refused rather
  than quietly permitted, so a future endpoint cannot become public by accident.
- **`AdminProperties` carries the same unresolved-placeholder guard as
  `MetaProperties`.** Without it, an unset `ADMIN_PASSWORD` would bind as the
  literal string `${ADMIN_PASSWORD}` and protect the rule API with a password
  published in this repository — the exact bug found in Phase 4.

### FINDING: `@WebMvcTest` applies default security, not yours

Adding the security starter broke all five `WebhookControllerTest` cases: GET
returned `401` and POST `403`. The slice auto-configures Spring Security with its
*defaults* (everything authenticated, CSRF enforced) and does not pick up the
application's `SecurityConfig`.

The fix is to `@Import(SecurityConfig.class)` into the slice, which has the
side benefit of making that test prove the webhook exemption rather than merely
assume it. Any future web slice touching a secured path needs the same import.

### Gotcha: zsh does not word-split unquoted variables

`A="-u user:pass"; curl $A url` works in bash and fails in zsh — zsh passes the
whole string as a single argument, so curl never sees `-u` and every request
returns `401`. This looked exactly like broken authentication for several
minutes. Pass credentials directly: `curl -u "$USER:$PASSWORD"`.

### 2026-08-15 — Phase 10: test hardening, Acceptance verified

Both Acceptance checks pass:

- **`./mvnw clean verify` from a clean build with only Docker running** — run with
  every `META_*` and `ADMIN_*` variable explicitly unset from the environment.
  145/145 green, exit 0. That is the real proof the suite is hermetic: nothing in
  it depends on the developer's shell.
- **No test reaches Meta.** Zero references to a real Graph host in the build log;
  every HTTP call goes to a WireMock on a random localhost port.

**Singleton container: 5 starts → 1.** Spring caches one context per distinct
configuration and this project legitimately has several, so Postgres was starting
five times per build. It is now a static field in `TestcontainersConfiguration`,
started once.

Two details make that safe, and both are easy to get wrong:

- The container starts in a static initialiser rather than being started by
  Spring, so the first context to load brings it up and the rest attach.
- `@Bean(destroyMethod = "")` stops Spring calling `close()` on it. Without that,
  the first context evicted from the cache stops the container out from under
  every other context still using it.

**Honest note on wall clock:** the build did not get faster — roughly 65s before,
70s now. Phase 10 also added a fifth Spring context and Jacoco instrumentation,
which absorbed the saving. The win is that container startup no longer scales
with the number of contexts, so adding the sixth is cheap.

### FINDING: one shared database exposed cross-test pollution

Sharing a container immediately failed two tests that had always passed, in
classes Phase 10 did not touch:

- `DomainPersistenceTest.seedRulesFromV2ArePresentAndDisabled` — the new
  end-to-end test's `rules.deleteAll()` was committing, taking the V2 seed rows
  with it.
- `EventProcessorTest.worksAgainstARealCapturedInstagramFixture` — a duplicate
  `event_key`, because the end-to-end test had committed a row using the same
  real fixture id.

Separate containers had been hiding both. The fix is scoped cleanup: the
end-to-end test deletes only rules it created, and clears its own rows in both
`@BeforeEach` and `@AfterEach`.

### FINDING: the end-to-end test must NOT be `@Transactional`

The obvious fix for the pollution above — annotate the class and let it roll back
— makes the test fail in a much more confusing way: **zero Graph API calls**.
Wrapping the whole flow in one transaction means the webhook insert, the
processor and the dispatcher all share it, and the `FOR UPDATE SKIP LOCKED`
claims find nothing to claim.

Committing between stages is also what actually happens in production, so the
non-transactional version is the more faithful test. It is the one class in the
suite that must clean up after itself explicitly.

### Phase 10: Jacoco

Line coverage is gated at 70% on `engine` and `delivery` only — the packages that
decide what to send and then send it. Enforcing it project-wide would turn the
number into a target to game rather than a signal.

Current: `engine` 82%, `delivery` 92%, `delivery.exceptions` 75%.

**The gate was proven to bite**, not merely to pass: temporarily raising the
threshold to 99% produced `BUILD FAILURE` with
`Rule violated for package ...: lines covered ratio is 0.82, but expected minimum
is 0.99`. Restored to 0.70 afterwards.

### 2026-08-15 — Phase 11: agent tasks complete, go-live PENDING

Agent-side work done and verified; the go-live itself is human and has not
happened. **`META_DRY_RUN` is still `true`.**

Built and verified end to end in Docker:

- Multi-stage `Dockerfile`, layered-jar extraction, non-root user, 400 MB image.
- `app` service in `docker-compose.yml` under a `profiles: ["app"]` guard, so
  `docker compose up -d postgres` remains the local-development path with the
  debugger attached. `depends_on: postgres: condition: service_healthy`.
- Readiness gate at `/actuator/health/readiness`
  (`group.readiness.include: readinessState,db`), used by the container
  `HEALTHCHECK`.

Container reached `healthy` in ~24s. Through the container: webhook handshake
`200`, admin API `401` unauthenticated / `200` authenticated, `META_DRY_RUN=true`,
process running as `uid=100(app)` rather than root.

**The readiness gate was verified against a paused database**, not just asserted:
with `docker pause metaautoreply-postgres`, `/actuator/health/readiness` stopped
answering while `/actuator/health/liveness` stayed `UP` — which is the correct
split. An orchestrator stops routing traffic but does not restart a process that
is merely waiting on its database. Note it *times out* rather than returning a
`DOWN` body, because the paused connection hangs; for the `HEALTHCHECK` and any
load balancer that is equivalent to not-ready, but it is worth knowing the failure
looks like a timeout rather than a clean status.

### Phase 11: Dockerfile notes

- **Tests are skipped in the image build.** They need a Docker daemon
  (Testcontainers), which does not exist inside an image build. `./mvnw verify` on
  the host is the gate; the image only packages what that gate approved.
- **Only two of the four extracted layers are copied.** `snapshot-dependencies`
  and `spring-boot-loader` come out empty for this project — there are no SNAPSHOT
  dependencies, and `-Djarmode=tools extract` produces a directly executable jar
  rather than one needing the Boot launcher. `COPY` of an empty directory fails
  the build, so those lines are deliberately absent; add them back if a SNAPSHOT
  dependency is ever introduced.
- **`.dockerignore` excludes `.env`** so credentials never enter the build context.
- The image is 400 MB, mostly the Alpine JRE. A `jlink` custom runtime would cut
  it substantially if that ever matters.

### FINDING: one intermittent full-suite failure, not reproduced

During this phase a full run failed with 11 failures in `OutboundDispatcherTest`
and `EndToEndFlowTest` — all consistent with `lockBatch` claiming nothing
(`attempts` still 0, `next_attempt_at` unmoved). It did not reproduce: the same
suite then passed three consecutive full runs, and both classes passed in
isolation and paired.

Root cause not established. The rate limiter was ruled out (no "Rate limit
reached" in the log) and clock skew was measured at +59 ms with the database
*ahead* of the host, which would make rows more claimable rather than less.

Hardening applied regardless: `OutboundDispatcherTest` now backdates
`next_attempt_at` by 30 seconds when queuing, so due-ness never sits on the
boundary between the JVM clock that stamps the row and the SQL `now()` that
compares it. That removes the most plausible remaining source of nondeterminism.
**If this recurs, it is a real bug and the shared database is the place to look**
— before Phase 10 each context had its own, so cross-context interference was
impossible by construction.

### 2026-08-15 — Phase 11 go-live: first real send, and a missing permission

`META_DRY_RUN=false`. First live attempt on a Facebook comment:

| Kind | Result |
|---|---|
| `PRIVATE_REPLY` (DM to the commenter) | **SENT** — real provider id `m_i6jAU…` |
| `PUBLIC_REPLY` (reply on the comment thread) | `FAILED` — HTTP 403, code 200 |

So the messaging half of the service works against a live account. Only
publishing a comment reply is blocked.

```
(#200) The permission(s) pages_read_user_content are not available.
It could because either they are deprecated or need to be approved by App Review.
```

The error classifier behaved correctly: code 200 is FATAL, so the message failed
once with `attempts = 0` rather than retrying into a rate limit, and the full
body was logged at ERROR.

### FINDING: PLAN.md Phase 0 omits two Page permissions

`debug_token` against the live System User token:

| Permission | Present | Needed for |
|---|---|---|
| `pages_messaging` | yes | DMs and private replies — working |
| `pages_read_engagement` | yes | reading Page metadata |
| `instagram_manage_comments` | yes | Instagram comment replies |
| **`pages_read_user_content`** | **no** | reading user-generated comments on a Page post |
| **`pages_manage_engagement`** | **no** | publishing a reply to a comment on a Page post |

`PLAN.md` Phase 0 lists `pages_messaging`, `pages_manage_metadata`,
`pages_read_engagement`, `pages_show_list`, `instagram_basic`,
`instagram_manage_comments`, `instagram_manage_messages` — enough for everything
except replying publicly on Facebook.

**Fix:** regenerate the System User token with `pages_read_user_content` and
`pages_manage_engagement` added. Both are available at standard access in
Development Mode for accounts holding a role on the app; advanced access (App
Review) is only needed to serve people with no role, which is Phase 12 anyway.

Instagram is unaffected — `instagram_manage_comments` covers IG public replies,
and that path has not yet been exercised live.

**Requeuing after the token is fixed:** the failed row cannot simply be recreated,
because `unique (kind, target_id)` (compliance invariant #1) blocks a second
`PUBLIC_REPLY` for the same comment. Reset the existing row instead:

```sql
update outbound_message
set status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = null
where status = 'FAILED' and kind = 'PUBLIC_REPLY';
```

### FINDING: publishing comments needs a PAGE token, not a SYSTEM_USER one

After adding `pages_read_user_content` and `pages_manage_engagement`, the public
reply failed differently:

```
(#3) Publishing comments through the API is only available for page access tokens
```

`debug_token` showed the regenerated token is type **`SYSTEM_USER`**. The
permissions were now correct but the token type was not — comment publishing
requires a **`PAGE`** token. The original token happened to be type `PAGE`, which
is why DMs worked from the start.

Diagnosing further showed the real blocker. Every entry in `granular_scopes` had
`target_ids: null`, and `GET /me/accounts` returned an empty list:

```
granular_scopes:
   - pages_manage_engagement -> None
   - pages_read_user_content -> None
```

A System User with a Page assigned lists that Page's id as the target. All-null
targets means **no Page is assigned to the System User**, so there is no Page for
it to mint a token for — which is also why
`GET /{page-id}?fields=access_token` returned `(#10) ... requires the
'pages_read_engagement' permission` despite the token holding exactly that scope.
The permission was present; the asset was not.

**Fix, in order:**

1. Business Settings → Users → System Users → (your user) → **Add Assets** →
   Pages → select the Page → enable full control. Add the Instagram account too.
2. Generate a new System User token with the same permissions.
3. Put it in `.env` and run `./scripts/derive-page-token.sh`, which exchanges it
   for a Page token and writes that back to `META_ACCESS_TOKEN`.

A Page token derived from a never-expiring System User token also never expires,
so step 3 is one-off rather than something to schedule.

`scripts/derive-page-token.sh` detects the unassigned-asset case and prints these
steps rather than failing obscurely. It never prints the token, writes only to
`.env` (gitignored), and backs up the previous file as `.env.bak`.

**PLAN.md gap:** Phase 0 says to "create a System User, assign the Page and
Instagram account, and generate a token". It does not mention that the resulting
System User token is the wrong *type* for comment publishing, nor that a Page
token must be derived from it.

## Real Meta payload observations

**STATUS: COMPLETE 2026-08-15.** Both platforms captured, comments and messages.
Phase 5 Acceptance met.

Fixtures in `src/test/resources/fixtures/`: `ig-comment-{1,2,3}.json`,
`ig-message-{1,2,3}.json`, `fb-feed-1.json`, `fb-message-{1,2,3}.json`.

Not captured: Instagram story reply (`PLAN.md` lists it; no fixture yet, so
Phase 6 must not attempt to parse one).

### The two platforms disagree on almost every field name

This table is the single most important output of Phase 5. Writing the Phase 6
normalizer from the Instagram shape alone would have silently broken Facebook.

| Wanted | Instagram comment | Facebook comment |
|---|---|---|
| `field` value | `comments` | **`feed`** |
| is it a comment? | implied by `field` | **`value.item == "comment"`** |
| comment id | `value.id` | **`value.comment_id`** |
| comment text | `value.text` | **`value.message`** |
| commenter id | `value.from.id` | `value.from.id` |
| commenter display | `value.from.username` | **`value.from.name`** |
| parent media/post | `value.media.id` | **`value.post_id`** |
| `verb` | **absent** | `value.verb` (`"add"`) |
| created time | **absent** | `value.created_time` (epoch **seconds**) |

Messages are closer but not identical:

| Wanted | Instagram message | Facebook message |
|---|---|---|
| sender | `sender.id` | `sender.id` |
| our account | `recipient.id` (IG user id) | `recipient.id` (**Page id**) |
| message id | `message.mid` | `message.mid` |
| text | `message.text` | `message.text` |
| timestamp | `timestamp` (epoch **millis**) | `timestamp` (epoch **millis**) |

### Consequences for Phase 6

1. **`field: "feed"` is not a synonym for "comment".** A Facebook feed change
   also covers posts, reactions, shares and edits. The normalizer must require
   `value.item == "comment"` and ignore everything else, or the service will
   react to its own posts and to likes.
2. **`verb` filtering must tolerate absence.** Facebook sends `verb: "add"`;
   Instagram sends no `verb` at all. `"add".equals(verb)` would discard every
   Instagram comment. Treat missing as actionable; filter only when present and
   not `add`.
3. **Text lives under different keys** — `value.text` (IG) vs `value.message`
   (FB). Same for the display name: `username` vs `name`.
4. **Timestamp units differ.** `value.created_time` is in *seconds*; the
   messaging `timestamp` is in *milliseconds*. Mixing them up moves dates by a
   factor of 1000 — and compliance invariant #2 (the 7-day private reply window)
   depends on getting this right.
5. **Instagram comments carry no timestamp at all.** The 7-day window check
   therefore cannot be computed from an IG comment payload. Use
   `inbound_event.received_at` as the proxy — sound because we receive events in
   near real time — and note that a backlog replay would make it optimistic.
   `TODO(human):` confirm this is acceptable before Phase 8 goes live.

### Confirmations

- **Instagram arrives with `object: "instagram"`**, Facebook with `object: "page"`.
  Both stored rows came out with the correct `platform`.
- **`recipient.id` on a Facebook message is `824570447415713`** — matching
  `META_PAGE_ID`, independently confirming that value.
- **Both guessed id paths in `WebhookIngestService` were right.**
  `value.comment_id` (used by Facebook) with a fallback to `value.id` (used by
  Instagram) covers both, and `message.mid` covers messages on both.
- Facebook comments are stored with `event_type = FEED`, since the ingest layer
  derives the type from the `field` name and deliberately does not interpret
  payloads. Phase 6 resolves FEED + `item=comment` into a comment.

### Note on committed fixtures

The fixtures contain real identifiers and a real display name from the test
interactions, and `src/test/resources/fixtures/` is tracked by git. The field
*shapes* are what Phase 6 needs, not the values, so these can be scrubbed to
dummy values without weakening the tests.

### Instagram comment (`field: "comments"`)

```json
{ "field": "comments",
  "value": { "id": "...", "from": { "id": "...", "username": "..." },
             "text": "...", "media": { "id": "...", "media_product_type": "REELS" } } }
```

| Wanted | Real path |
|---|---|
| comment id | `value.id` |
| commenter id | `value.from.id` |
| commenter username | `value.from.username` |
| comment text | `value.text` |
| parent media id | `value.media.id` |
| `verb` | **absent — see below** |

### Instagram message

```json
{ "sender": { "id": "..." }, "recipient": { "id": "<our IG user id>" },
  "message": { "mid": "...", "text": "..." }, "timestamp": 1786747628183 }
```

| Wanted | Real path |
|---|---|
| sender id (IGSID) | `sender.id` |
| our own account id | `recipient.id` |
| message id | `message.mid` |
| text | `message.text` |
| timestamp | `timestamp` (epoch **milliseconds**) |
| username | **absent** — messages carry no username, unlike comments |

### Answers to the Phase 5 questions

- **Instagram arrives with `object: "instagram"`.** Confirmed indirectly but
  reliably: `WebhookIngestService` derives platform from the top-level `object`
  containing "instagram", and every stored row came out `IG`.
- **The guessed paths held.** Comment id resolved through the `value.comment_id`
  → `value.id` fallback, and `message.mid` was correct. The `value.comment_id`
  branch is unused for Instagram and can be dropped once Facebook is confirmed.

### FINDING: there is no `verb` field on Instagram comments

`PLAN.md` Phase 6 says to filter out events whose `verb` is anything other than
`add`. Instagram comment payloads have **no `verb` field at all**. A naive
`"add".equals(verb)` check would therefore discard every real Instagram comment
and the service would do nothing.

Phase 6 must treat a missing `verb` as actionable, and only filter when the
field is present and not `add`. Facebook `feed` changes are the ones that
genuinely carry `verb` (add/edited/remove) — confirm when FB capture happens.

### FINDING: META_PAGE_ID and META_IG_USER_ID are swapped in `.env`

The captured message payload has `recipient.id = 17841403243590103` — the
account receiving the DM, i.e. our own Instagram Business Account. That value is
currently set as `META_PAGE_ID`, and `META_IG_USER_ID` holds `1534003527925532`.

They are the wrong way round. `17841...` is the standard Instagram Business
Account ID form. This matters directly: compliance invariant #4 (no self-replies)
compares an event's sender against the configured Page ID and IG User ID, so with
the values swapped the service would fail to recognise its own replies and could
answer itself in a loop.

**Action:** set `META_IG_USER_ID=17841403243590103` and put the real Facebook
Page ID in `META_PAGE_ID`. Do this before Phase 6 wires up the self-reply filter.

### 2026-08-15 — IDs confirmed against the Graph API

With the System User token in place, `GET /{page-id}?fields=id,name,instagram_business_account`
returned:

```json
{"id":"824570447415713","name":"The Explosive Guy",
 "instagram_business_account":{"id":"17841403243590103"}}
```

That settles the swap definitively:

- `META_PAGE_ID=824570447415713` — correct.
- `META_IG_USER_ID=17841403243590103` — corrected; it is the IG account linked
  to that Page, and it matches the `recipient.id` seen in captured DMs.

### FINDING: `.env` inline comments corrupt the access token

The token line briefly carried a trailing `# <old IGAA token>` note. Bash treats
that as a comment, so `source .env` was fine — but the VS Code `envFile` parser
takes everything after the first `=` as the value. Measured: 217 characters via
`source`, **400 characters via envFile**, including the stale token and the `#`.

Since the app is normally launched from VS Code, this would have sent a corrupt
bearer token on every Graph call in Phase 7, with a confusing auth error. Rule:
**never put a trailing comment on a value line in `.env`.** Comments go on their
own line.

### FINDING: an app can have TWO webhook signing secrets

**Confirmed 2026-08-15 from the running app's log:** Facebook deliveries were
arriving and being refused with `Rejecting webhook delivery: invalid signature`.
They were never missing — we were rejecting them.

An app using **Instagram API with Instagram Login** has two distinct secrets:

| Secret | Where | Signs |
|---|---|---|
| Facebook App Secret | App Settings → Basic | Page deliveries (`feed`, `messages`) |
| Instagram App Secret | Instagram product settings | Instagram deliveries |

`META_APP_SECRET` held the Instagram one. The contradiction that exposed this:
the same secret **verified real Instagram webhook signatures** (rows kept
landing) while Meta **rejected it** for both `appsecret_proof` and the
`{app-id}|{app-secret}` app access token. A secret cannot be simultaneously
right and wrong for one app — so there had to be two.

Diagnostics worth reusing:

- `GET /debug_token?input_token=<tok>&access_token=<tok>` → `app_id`,
  `application`, `type`, `scopes`. Confirms which app a token belongs to.
- `GET /me?access_token=<tok>&appsecret_proof=<HMAC-SHA256(tok, secret)>` →
  a clean pass/fail on whether a secret matches the app.

**Fix:** `SignatureVerifier` now accepts a delivery matching **any** configured
secret (`meta.app-secret` plus optional `meta.instagram-app-secret`). It cannot
choose per platform, because the platform is only knowable by parsing the body,
and parsing an unverified body is exactly what must not happen. All candidates
are evaluated even after a match, so timing does not reveal which key matched.
A rejection now logs how many secrets were tried and names the likely cause.

**Design lesson:** the Phase 4 assumption of a single app secret is correct only
for classic Page-only apps. This surfaced as *silence* rather than a bad fixture,
which is the harder failure mode to notice — the endpoint looked healthy and
Instagram traffic flowed the whole time.

### Why Facebook events were not arriving

**ROOT CAUSE 1 (fixed 2026-08-15).** `GET /{page-id}/subscribed_apps` returned
`{"data":[]}` — the Page is not subscribed to the app. Steps 1 and 2 were already
done (the System User token authenticates as the Page). Setting the webhook
callback URL in the dashboard is app-level configuration and does **not**
subscribe a Page; that is a separate Graph API call:

```
POST /{page-id}/subscribed_apps?subscribed_fields=feed,messages
```

Verify with `GET /{page-id}/subscribed_apps` — a non-empty `data` array means it
took. This is the step that is almost always missed when Page webhooks silently
deliver nothing.

## Unrecognised Graph API error codes

Populated during Phases 7–11. Record code, subcode, HTTP status, the situation
that produced it, and the classification chosen.
