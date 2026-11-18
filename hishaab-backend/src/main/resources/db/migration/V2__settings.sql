-- SPEC-001: typed, runtime-editable settings. Keys and value shapes are validated in code
-- (settings.internal.SettingDefinitions); unknown keys are rejected by the API.
CREATE TABLE settings (
  key        TEXT PRIMARY KEY,
  value      JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO settings (key, value) VALUES
  ('scan.cron',                    '"0 0 22 * * *"'),
  ('scan.zone',                    '"Asia/Kolkata"'),
  ('scan.serverDelayMinutes',      '15'),
  ('senders.allowlist',            '[]'),
  ('match.smsEmailWindowMinutes',  '180'),
  ('ai.categoryConfidenceMin',     '0.80'),
  ('backfill.days',                '31');
