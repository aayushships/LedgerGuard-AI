
CREATE TABLE refund_requests (
    id BIGSERIAL PRIMARY KEY,

    capture_id VARCHAR(100) NOT NULL,

    amount_minor BIGINT NOT NULL
        CHECK (amount_minor > 0),

    currency CHAR(3) NOT NULL,

    reason VARCHAR(255) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN (
            'PENDING',
            'APPROVED',
            'PROCESSING',
            'COMPLETED',
            'REJECTED',
            'FAILED'
        )),

    paypal_refund_id VARCHAR(100) UNIQUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_capture
ON refund_requests(capture_id);

CREATE INDEX idx_refund_status
ON refund_requests(status);
