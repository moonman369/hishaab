-- SPEC-010: registered phones (Android SMS worker) and their upload tokens. Only the token hash is stored.
CREATE TABLE device (
  id            UUID PRIMARY KEY,
  name          TEXT NOT NULL,
  token_hash    TEXT NOT NULL UNIQUE,
  fcm_token     TEXT,
  sms_cursor_ms BIGINT,
  last_sync_at  TIMESTAMPTZ,
  revoked_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE raw_message ADD COLUMN device_id UUID REFERENCES device(id);
