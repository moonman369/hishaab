-- SPEC-000 / HLD §4.7: raw_message is the append-only source of truth (H-2, HLD D-06).
-- device_id is added by V3 (SPEC-010).
CREATE TABLE raw_message (
  id            TEXT        PRIMARY KEY,               -- SMS: sha256 hex (SPEC §4.1); EMAIL: "gmail:<id>" (§4.2)
  source        TEXT        NOT NULL CHECK (source IN ('SMS', 'EMAIL')),
  sender        TEXT        NOT NULL,
  body          TEXT        NOT NULL,
  received_at   TIMESTAMPTZ NOT NULL,
  ingested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  status        TEXT        NOT NULL DEFAULT 'NEW'
                CHECK (status IN ('NEW', 'CLASSIFIED', 'PARSED', 'IGNORED', 'FAILED', 'ERROR')),
  ignore_reason TEXT
);

CREATE INDEX raw_message_status_idx ON raw_message (status);
CREATE INDEX raw_message_received_at_idx ON raw_message (received_at);

-- H-2 enforced in the database: content columns are immutable and rows cannot be deleted.
-- Only status and ignore_reason may change.
CREATE FUNCTION raw_message_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'raw_message rows are append-only and cannot be deleted (H-2)';
  END IF;
  IF NEW.id          IS DISTINCT FROM OLD.id
  OR NEW.source      IS DISTINCT FROM OLD.source
  OR NEW.sender      IS DISTINCT FROM OLD.sender
  OR NEW.body        IS DISTINCT FROM OLD.body
  OR NEW.received_at IS DISTINCT FROM OLD.received_at
  OR NEW.ingested_at IS DISTINCT FROM OLD.ingested_at THEN
    RAISE EXCEPTION 'raw_message content is immutable; only status and ignore_reason may change (H-2)';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER raw_message_guard
  BEFORE UPDATE OR DELETE ON raw_message
  FOR EACH ROW EXECUTE FUNCTION raw_message_guard();
