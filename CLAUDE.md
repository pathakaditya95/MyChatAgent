# CLAUDE.md — meta-autoreply

Spring Boot service that auto-replies to Facebook and Instagram comments and
sends automated DMs, driven by keyword rules stored in Postgres.

`PLAN.md` is the source of truth for scope. This file carries the conventions
that survive across sessions.

## Execution rules

1. **Do not skip ahead.** Execute phases in order. Each phase compiles and
   passes its own Acceptance checks before the next one starts.
2. **Do not invent Graph API payload shapes.** Phase 5 captures real payloads
   first. Parsers are written against captured fixtures in
   `src/test/resources/fixtures/`, never against assumptions.
3. **Do not hardcode secrets.** Everything sensitive comes from environment
   variables, surfaced through `@ConfigurationProperties`.
4. **Run `./mvnw -q verify` after every phase.** Fix failures before
   continuing. Always the wrapper — on this machine the Homebrew `mvn` resolves
   JDK 26 while `./mvnw` correctly resolves JDK 21. See `NOTES.md`.
5. **Prefer constructor injection.** No field `@Autowired`.
6. **Prefer `RestClient`** over `RestTemplate` or `WebClient`.
7. **If a library version in `PLAN.md` is stale**, use the current stable
   release and record the change in `NOTES.md`.
8. **When something is ambiguous**, write the simplest thing that satisfies
   Acceptance and add a `TODO(human):` comment. Do not silently guess at Meta
   semantics.

## Package layout

Base package `com.example.metaautoreply`.

| Package     | Holds                                                              |
|-------------|--------------------------------------------------------------------|
| `config`    | `AppProperties`, `MetaProperties`, `RestClientConfig`, `SchedulingConfig`, `SecurityConfig` |
| `security`  | `SignatureVerifier`                                                |
| `web`       | `WebhookController`, `RuleAdminController`, `DebugController`, `dto/` |
| `domain`    | `InboundEvent`, `Contact`, `KeywordRule`, `OutboundMessage`, `enums/` |
| `repo`      | One Spring Data repository per entity                              |
| `ingest`    | `WebhookIngestService`, `payload/EventNormalizer`                  |
| `engine`    | `EventProcessor`, `RuleMatcher`, `NormalizedEvent`                 |
| `delivery`  | `OutboundDispatcher`, `GraphApiClient`, `SendRateLimiter`, `MetaErrorClassifier`, `exceptions/` |

Migrations live in `src/main/resources/db/migration`, named `V<n>__<snake_case>.sql`.
Migrations are append-only — never edit one that has already been applied.

## Compliance invariants — never remove

1. **One private reply per comment.** Enforced by `unique (kind, target_id)` on
   `outbound_message`. Never drop this constraint.
2. **7-day private reply window.** A comment older than 7 days cannot receive a
   private reply — check before queueing.
3. **24-hour messaging window.** Any DM not triggered by a comment requires an
   interaction within the last 24 hours. Check `contact.last_interaction_at`.
4. **No self-replies.** Filter events whose sender is the configured Page ID or
   IG User ID, or the service replies to itself in a loop.
5. **Honour opt-out.** A message containing `stop` or `unsubscribe` sets
   `contact.opted_out = true` and suppresses all future sends.
6. **Pace sends.** Never disable the rate limiter.

Violating these risks the Facebook Page and Instagram account, not just the
service.

## Webhook handling — the usual failure modes

- `GET /webhook` must echo `hub.challenge` as **plain text**, not JSON.
- `POST /webhook` must bind the body as `String`. Binding to a DTO makes
  Jackson re-serialize the bytes, and the HMAC signature will never match.
- Compare signatures with `MessageDigest.isEqual`, never `String.equals`.
- **Always return 200** after a successful signature check, even on internal
  errors. A non-200 makes Meta retry and eventually disable the subscription.

## Testing

- Integration tests use Testcontainers Postgres; Graph API calls are stubbed
  with WireMock. No test may reach the real Meta API.
- `meta.dry-run` defaults to `true` so development never sends live traffic.
