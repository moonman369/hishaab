-- SPEC-013: versioned parse templates (loaded from parse-templates/*.yml at startup) and parsed records.

CREATE TABLE parse_template (
  id             TEXT        NOT NULL,
  version        INT         NOT NULL CHECK (version >= 1),
  bank           TEXT        CHECK (bank IN ('AXIS', 'SBI')),          -- null for fund-house (AMC) templates
  source         TEXT        NOT NULL CHECK (source IN ('SMS', 'EMAIL')),
  sender_pattern TEXT        NOT NULL,
  regex          TEXT        NOT NULL,
  regex_hash     TEXT        NOT NULL,                                 -- AC-013-3: detects edits without a version bump
  fixed          JSONB       NOT NULL DEFAULT '{}',
  account_type   TEXT        CHECK (account_type IN ('SAVINGS', 'CREDIT_CARD')),
  record_type    TEXT        NOT NULL CHECK (record_type IN ('TRANSACTION', 'INVESTMENT_CONFIRMATION')),
  active         BOOLEAN     NOT NULL DEFAULT true,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, version)
);

CREATE TABLE parsed_record (
  id                  UUID        PRIMARY KEY,
  raw_message_id      TEXT        NOT NULL UNIQUE REFERENCES raw_message (id),
  template_id         TEXT,
  template_version    INT,
  parser              TEXT        NOT NULL CHECK (parser IN ('TEMPLATE', 'LLM')),
  record_type         TEXT        NOT NULL CHECK (record_type IN ('TRANSACTION', 'INVESTMENT_CONFIRMATION')),
  amount_paise        BIGINT      NOT NULL CHECK (amount_paise >= 0),
  direction           TEXT        NOT NULL CHECK (direction IN ('DR', 'CR')),
  account_last4       TEXT        CHECK (account_last4 ~ '^[0-9]{4}$'),
  channel             TEXT        NOT NULL CHECK (channel IN ('UPI', 'CARD', 'NEFT', 'IMPS', 'RTGS', 'ATM', 'AUTOPAY', 'OTHER')),
  counterparty_raw    TEXT,
  ref_no              TEXT        CHECK (ref_no ~ '^[0-9]{6,22}$'),
  txn_time            TIMESTAMPTZ NOT NULL,
  balance_after_paise BIGINT,
  confidence          NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (template_id, template_version) REFERENCES parse_template (id, version),
  CHECK (parser = 'LLM' OR (template_id IS NOT NULL AND template_version IS NOT NULL))
);

CREATE INDEX parsed_record_ref_idx ON parsed_record (ref_no) WHERE ref_no IS NOT NULL;
CREATE INDEX parsed_record_txn_time_idx ON parsed_record (txn_time);
