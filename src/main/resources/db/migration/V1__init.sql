-- Chart of accounts
CREATE TABLE accounts (
    code VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

INSERT INTO accounts (code, name) VALUES
    ('PAYPAL_CLEARING', 'Money held at PayPal (asset)'),
    ('SALES_REVENUE',   'Sales revenue (income)');

-- One row per business event (a payment, a refund)
CREATE TABLE ledger_transactions (
    id          BIGSERIAL PRIMARY KEY,
    reference   VARCHAR(100) NOT NULL UNIQUE,   -- e.g. PayPal capture id; stops double posting
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Two or more rows per transaction. Amounts are in minor units (cents/paise).
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES ledger_transactions(id),
    account_code   VARCHAR(50) NOT NULL REFERENCES accounts(code),
    direction      CHAR(1) NOT NULL CHECK (direction IN ('D', 'C')),
    amount_minor   BIGINT NOT NULL CHECK (amount_minor > 0),
    currency       CHAR(3) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_txn ON ledger_entries(transaction_id);

-- Append-only: block any update or delete on the ledger
CREATE FUNCTION forbid_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger is append-only. Post a reversing entry instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TRIGGER txns_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- Every webhook we have seen. The primary key is what makes duplicates harmless.
CREATE TABLE webhook_events (
    event_id    VARCHAR(100) PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    payload     TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


