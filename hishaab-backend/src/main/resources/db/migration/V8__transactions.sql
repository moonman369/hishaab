-- SPEC-031: transactions, transaction evidence, and FKs. HLD §4.4 / §4.7.

CREATE TABLE transaction (
  id                  UUID        PRIMARY KEY,
  amount_paise        BIGINT      NOT NULL CHECK (amount_paise >= 0),
  direction           TEXT        NOT NULL CHECK (direction IN ('DR', 'CR')),
  txn_time            TIMESTAMPTZ NOT NULL,
  account_id          UUID        REFERENCES account (id) ON DELETE SET NULL,
  merchant_id         UUID        REFERENCES merchant (id) ON DELETE SET NULL,
  category_id         UUID,       -- FK added by SPEC-043 (categories)
  category_source     TEXT        NOT NULL DEFAULT 'NONE' CHECK (category_source IN ('USER', 'RULE', 'AI', 'NONE')),
  ai_confidence       NUMERIC(4, 3) CHECK (ai_confidence IS NULL OR ai_confidence BETWEEN 0 AND 1),
  ai_model_version    TEXT,
  kind                TEXT        NOT NULL CHECK (kind IN ('SPEND', 'INCOME', 'TRANSFER', 'REFUND', 'CC_PAYMENT', 'REVERSAL', 'INVESTMENT')),
  status              TEXT        NOT NULL CHECK (status IN ('CONFIRMED', 'PENDING', 'HIDDEN')),
  channel             TEXT        NOT NULL CHECK (channel IN ('UPI', 'CARD', 'NEFT', 'IMPS', 'RTGS', 'ATM', 'AUTOPAY', 'OTHER')),
  ref_no              TEXT        CHECK (ref_no IS NULL OR ref_no ~ '^[0-9]{6,22}$'),
  note                TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- R-031-1: Unique ref_no + direction across non-hidden transactions.
-- Ref + direction because a reversal can reuse the original ref with opposite direction.
CREATE UNIQUE INDEX ux_txn_ref_dir ON transaction(ref_no, direction)
  WHERE ref_no IS NOT NULL AND status <> 'HIDDEN';

CREATE INDEX ix_txn_account_time ON transaction(account_id, txn_time);
CREATE INDEX ix_txn_time ON transaction(txn_time);

CREATE TABLE transaction_evidence (
  id                  UUID        PRIMARY KEY,
  transaction_id      UUID        NOT NULL REFERENCES transaction (id) ON DELETE CASCADE,
  parsed_record_id    UUID        NOT NULL REFERENCES parsed_record (id) ON DELETE CASCADE,
  match_method        TEXT        NOT NULL CHECK (match_method IN ('FIRST', 'REF', 'WINDOW', 'MANUAL')),
  score               NUMERIC(4, 3),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- R-031-7 / AC-031-7: A parsed record can only be attached as evidence once.
CREATE UNIQUE INDEX ux_evidence_record ON transaction_evidence(parsed_record_id);
CREATE INDEX ix_evidence_txn ON transaction_evidence(transaction_id);

-- Add FK on review_item.transaction_id to transaction
ALTER TABLE review_item
  ADD CONSTRAINT fk_review_transaction
  FOREIGN KEY (transaction_id) REFERENCES transaction (id) ON DELETE SET NULL;

-- Update review_item type check constraint to include EMAIL_ONLY and BALANCE_STALE
ALTER TABLE review_item DROP CONSTRAINT review_item_type_check;
ALTER TABLE review_item ADD CONSTRAINT review_item_type_check CHECK (type IN (
  'AMBIGUOUS_MATCH', 'UNPARSED', 'LOW_CONFIDENCE', 'UNKNOWN_CATEGORY',
  'UNKNOWN_KIND', 'BALANCE_GAP', 'ORPHAN_OVERRIDE',
  'INSTALMENT_AMBIGUOUS', 'SIP_SCHEDULE_SUGGESTION', 'EMAIL_ONLY', 'BALANCE_STALE'
));
