-- Decision 2026-11-18 (SPEC Q-8): sender allowlist is open by default ("*" = accept every sender) while fund-house
-- sender IDs are unknown; the sync worker filters on its side. Only changes the value if it is still the old
-- untouched default, so a list you already set is kept.
UPDATE settings SET value = '["*"]'::jsonb, updated_at = now()
WHERE key = 'senders.allowlist' AND value = '[]'::jsonb;
