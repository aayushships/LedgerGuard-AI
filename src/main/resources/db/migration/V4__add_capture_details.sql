
ALTER TABLE payment_attempts
ADD COLUMN paypal_capture_id VARCHAR(100) UNIQUE;

-- Helps find attempts by their PayPal order ID.
CREATE INDEX idx_payment_attempts_paypal_order
ON payment_attempts(paypal_order_id);
    