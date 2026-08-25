-- Raw webhook deliveries, one row per event split out of the entry[] array.
-- Written by the webhook endpoint (Phase 4) and drained by EventProcessor (Phase 6).
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
-- Supports the `status = 'NEW' order by received_at ... for update skip locked` drain.
create index idx_inbound_event_status on inbound_event (status, received_at);

-- One row per person we have interacted with, per platform.
-- last_interaction_at backs the 24-hour messaging window; opted_out backs the
-- stop/unsubscribe suppression. Both are compliance invariants.
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

-- User-editable keyword rules. Lowest priority value wins.
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

-- Outbound send queue, drained by OutboundDispatcher (Phase 8).
--
-- COMPLIANCE INVARIANT: `unique (kind, target_id)` enforces Meta's
-- "one private reply per comment" rule at the database level. It is the last
-- line of defence against duplicate sends if the dispatcher is ever run
-- concurrently or an event is redelivered. NEVER remove this constraint.
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
-- Supports the `status = 'PENDING' and next_attempt_at <= now()` dispatch drain.
create index idx_outbound_dispatch on outbound_message (status, next_attempt_at);
