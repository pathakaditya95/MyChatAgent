-- Two example rules so keyword_rule is never empty and the admin UI (Phase 9)
-- has something to render. Both are disabled: enabling one is a deliberate,
-- manual step taken during Phase 11 go-live.
insert into keyword_rule (name, platform, trigger_type, match_type, keyword, public_reply, dm_text, enabled, priority)
values
 ('IG price keyword', 'IG', 'COMMENT', 'CONTAINS', 'price',
  'Just sent you a DM with details!', 'Hi! Here are the details you asked for: https://example.com', false, 10),
 ('FB info keyword', 'FB', 'COMMENT', 'EXACT', 'info',
  'Check your inbox.', 'Thanks for commenting! Here is the info: https://example.com', false, 20);
