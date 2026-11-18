-- SPEC-033 data (created early for SPEC-015, which must open UNPARSED items). HLD §4.7 / §4.8.
-- FKs to transaction and scan_run are added by the specs that create those tables (031, 036).
CREATE TABLE review_item (
  id             UUID        PRIMARY KEY,
  type           TEXT        NOT NULL CHECK (type IN ('AMBIGUOUS_MATCH', 'UNPARSED', 'LOW_CONFIDENCE', 'UNKNOWN_CATEGORY',
                                                      'UNKNOWN_KIND', 'BALANCE_GAP', 'ORPHAN_OVERRIDE',
                                                      'INSTALMENT_AMBIGUOUS', 'SIP_SCHEDULE_SUGGESTION')),
  status         TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED', 'AUTO_CLOSED')),
  payload        JSONB       NOT NULL,
  resolution     JSONB,
  dedup_key      TEXT        NOT NULL UNIQUE,     -- R-033-4: the same question is never opened twice
  raw_message_id TEXT        REFERENCES raw_message (id),
  transaction_id UUID,
  scan_run_id    UUID,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at    TIMESTAMPTZ
);

CREATE INDEX review_item_status_type_idx ON review_item (status, type);
CREATE INDEX review_item_raw_message_idx ON review_item (raw_message_id) WHERE raw_message_id IS NOT NULL;
