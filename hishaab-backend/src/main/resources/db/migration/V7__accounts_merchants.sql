-- SPEC-030: accounts (+ card aliases) and merchants (+ raw-name aliases). These are NOT derived data:
-- rebuilds (R-034-3) never delete them, so renames, aliases and merges persist on their own.

CREATE TABLE account (
  id         UUID        PRIMARY KEY,
  bank       TEXT        NOT NULL CHECK (bank IN ('AXIS', 'SBI')),
  type       TEXT        NOT NULL CHECK (type IN ('SAVINGS', 'CREDIT_CARD')),
  last4      TEXT        NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
  nickname   TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (bank, last4)
);

-- R-030-5: extra last-4 numbers (e.g. a debit card) that resolve to an account.
CREATE TABLE account_alias (
  id         UUID        PRIMARY KEY,
  account_id UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
  bank       TEXT        NOT NULL CHECK (bank IN ('AXIS', 'SBI')),
  last4      TEXT        NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
  kind       TEXT        NOT NULL CHECK (kind IN ('DEBIT_CARD', 'CREDIT_CARD', 'OTHER')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (bank, last4)
);

-- A (bank, last4) belongs to exactly one account: either as its own number or as one alias, never both.
CREATE FUNCTION account_number_unique() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'account_alias' THEN
    IF EXISTS (SELECT 1 FROM account a WHERE a.bank = NEW.bank AND a.last4 = NEW.last4) THEN
      RAISE EXCEPTION 'ALIAS_CONFLICT: % % is already an account number', NEW.bank, NEW.last4 USING ERRCODE = '23505';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM account a WHERE a.id = NEW.account_id AND a.bank = NEW.bank) THEN
      RAISE EXCEPTION 'alias bank must match its account bank' USING ERRCODE = '23514';
    END IF;
  ELSE
    IF EXISTS (SELECT 1 FROM account_alias x WHERE x.bank = NEW.bank AND x.last4 = NEW.last4) THEN
      RAISE EXCEPTION 'ALIAS_CONFLICT: % % is already an alias', NEW.bank, NEW.last4 USING ERRCODE = '23505';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER account_number_unique BEFORE INSERT OR UPDATE OF bank, last4 ON account
  FOR EACH ROW EXECUTE FUNCTION account_number_unique();
CREATE TRIGGER account_alias_number_unique BEFORE INSERT OR UPDATE OF bank, last4, account_id ON account_alias
  FOR EACH ROW EXECUTE FUNCTION account_number_unique();

CREATE TABLE merchant (
  id                  UUID        PRIMARY KEY,
  name                TEXT        NOT NULL,
  type                TEXT        NOT NULL CHECK (type IN ('MERCHANT', 'PERSON')),
  default_category_id UUID,                      -- FK added by SPEC-043 (categories)
  always_ask          BOOLEAN     NOT NULL DEFAULT false,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE merchant_alias (
  id                  UUID        PRIMARY KEY,
  merchant_id         UUID        NOT NULL REFERENCES merchant (id) ON DELETE CASCADE,
  raw_name            TEXT        NOT NULL,      -- first raw string seen for this normalised name
  raw_name_normalised TEXT        NOT NULL UNIQUE,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX merchant_alias_merchant_idx ON merchant_alias (merchant_id);
