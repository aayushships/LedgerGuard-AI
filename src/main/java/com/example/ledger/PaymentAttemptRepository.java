
package com.example.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class PaymentAttemptRepository {

    public record PaymentAttempt(
            long id,
            long userId,
            BigDecimal amount,
            String currency,
            String status,
            String paypalOrderId,
            String paypalCaptureId,
            String approveUrl,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    private static final RowMapper<PaymentAttempt> ROW_MAPPER =
            (rs, rowNum) -> new PaymentAttempt(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency").trim(),
                    rs.getString("status"),
                    rs.getString("paypal_order_id"),
                    rs.getString("paypal_capture_id"),
                    rs.getString("approve_url"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class)
            );

    private final JdbcTemplate jdbc;

    public PaymentAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentAttempt> findActiveByUserId(long userId) {

        return jdbc.query(
                """
                SELECT *
                FROM payment_attempts
                WHERE user_id = ?
                  AND status IN (
                      'CREATED',
                      'ORDER_CREATED',
                      'APPROVAL_PENDING',
                      'CAPTURE_PENDING',
                      'PROCESSING'
                  )
                ORDER BY created_at DESC
                LIMIT 1
                """,
                ROW_MAPPER,
                userId
        ).stream().findFirst();
    }

    public PaymentAttempt create(
            long userId,
            BigDecimal amount,
            String currency) {

        return jdbc.queryForObject(
                """
                INSERT INTO payment_attempts
                    (user_id, amount, currency, status)
                VALUES (?, ?, ?, 'CREATED')
                RETURNING *
                """,
                ROW_MAPPER,
                userId,
                amount,
                currency
        );
    }

    public void savePayPalOrder(
            long attemptId,
            String paypalOrderId,
            String approveUrl) {

        int updated = jdbc.update(
                """
                UPDATE payment_attempts
                SET paypal_order_id = ?,
                    approve_url = ?,
                    status = 'APPROVAL_PENDING',
                    updated_at = now()
                WHERE id = ?
                  AND status = 'CREATED'
                """,
                paypalOrderId,
                approveUrl,
                attemptId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not update payment attempt: " + attemptId);
        }
    }

    /**
     * Records a successful PayPal capture response.
     * PROCESSING means the capture succeeded but webhook processing
     * has not yet confirmed the ledger update.
     */
    public void recordCapture(
            String paypalOrderId,
            String captureId) {

        int updated = jdbc.update(
                """
                UPDATE payment_attempts
                SET paypal_capture_id = ?,
                    status = 'PROCESSING',
                    updated_at = now()
                WHERE paypal_order_id = ?
                  AND status IN (
                      'APPROVAL_PENDING',
                      'CAPTURE_PENDING',
                      'PROCESSING'
                  )
                """,
                captureId,
                paypalOrderId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not record capture for PayPal order: "
                            + paypalOrderId);
        }
    }

    /**
     * Finds a payment attempt using its PayPal order ID.
     */
    public Optional<PaymentAttempt> findByPayPalOrderId(
            String paypalOrderId) {

        return jdbc.query(
                """
                SELECT *
                FROM payment_attempts
                WHERE paypal_order_id = ?
                """,
                ROW_MAPPER,
                paypalOrderId
        ).stream().findFirst();
    }

    /**
     * Marks the payment completed after webhook processing.
     */
    public void markCompletedByCaptureId(String captureId) {

        jdbc.update(
                """
                UPDATE payment_attempts
                SET status = 'COMPLETED',
                    updated_at = now()
                WHERE paypal_capture_id = ?
                """,
                captureId
        );
    }
}
