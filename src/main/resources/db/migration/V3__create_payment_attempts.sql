
CREATE TABLE payment_attempts (
    id BIGSERIAL PRIMARY KEY,

    user_id BIGINT NOT NULL,

    amount NUMERIC(12, 2) NOT NULL
        CHECK (amount > 0),

    currency CHAR(3) NOT NULL
        CHECK (currency ~ '^[A-Z]{3}$'),

    status VARCHAR(30) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN (
            'CREATED',
            'ORDER_CREATED',
            'APPROVAL_PENDING',
            'CAPTURE_PENDING',
            'PROCESSING',
            'COMPLETED',
            'CANCELLED',
            'FAILED'
        )),

    paypal_order_id VARCHAR(100) UNIQUE,

    approve_url TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One active payment per customer.
CREATE UNIQUE INDEX one_active_payment_per_customer
ON payment_attempts(user_id)
WHERE status IN (
    'CREATED',
    'ORDER_CREATED',
    'APPROVAL_PENDING',
    'CAPTURE_PENDING',
    'PROCESSING'
);

CREATE INDEX idx_payment_attempts_user
ON payment_attempts(user_id);
