
package com.example.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
public class WebhookController {

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final PayPalClient paypal;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PaymentAttemptRepository paymentAttemptRepository;

    public WebhookController(
        JdbcTemplate jdbc,
        LedgerService ledger,
        PayPalClient paypal,
        PaymentAttemptRepository paymentAttemptRepository) {

    this.jdbc = jdbc;
    this.ledger = ledger;
    this.paypal = paypal;
    this.paymentAttemptRepository = paymentAttemptRepository;
}

    @PostMapping("/webhooks/paypal")
    @Transactional
    public ResponseEntity<String> receive(
            @RequestBody String body,
            @RequestHeader HttpHeaders headers) throws Exception {

        JsonNode event = mapper.readTree(body);

        // Verify PayPal signature.
        if (paypal.webhookConfigured()) {

            boolean hasSignatureHeaders =
                    headers.containsKey("PAYPAL-AUTH-ALGO")
                    && headers.containsKey("PAYPAL-CERT-URL")
                    && headers.containsKey("PAYPAL-TRANSMISSION-ID")
                    && headers.containsKey("PAYPAL-TRANSMISSION-SIG")
                    && headers.containsKey("PAYPAL-TRANSMISSION-TIME");

            if (!hasSignatureHeaders) {
                return ResponseEntity.badRequest()
                        .body("Missing PayPal signature headers");
            }

            if (!paypal.verifyWebhook(headers, event)) {
                return ResponseEntity.status(401)
                        .body("PayPal signature verification failed");
            }
        }

        String eventId = event.path("id").asText();
        String type = event.path("event_type").asText();

        // Store event only once.
        int inserted = jdbc.update(
                """
                INSERT INTO webhook_events
                    (event_id, event_type, payload)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId, type, body
        );

        if (inserted == 0) {
            return ResponseEntity.ok("duplicate ignored");
        }

        JsonNode resource = event.path("resource");

        switch (type) {

            case "PAYMENT.CAPTURE.COMPLETED" -> {

                String captureId = resource.path("id").asText();
                long minor = toMinor(
                        resource.path("amount").path("value").asText("0"));

                String currency = resource.path("amount")
                        .path("currency_code").asText("USD");

                ledger.post(
                        "capture:" + captureId,
                        "PayPal payment " + captureId,
                        List.of(
                                new LedgerService.Line(
                                        "PAYPAL_CLEARING", 'D', minor, currency),
                                new LedgerService.Line(
                                        "SALES_REVENUE", 'C', minor, currency)
                        )
                );

                // Mark the matching payment attempt as completed.
                paymentAttemptRepository.markCompletedByCaptureId(captureId);
            }

            case "PAYMENT.CAPTURE.REFUNDED" -> {

                // For a refund event, resource.id is the REFUND ID.
                String refundId = resource.path("id").asText();

                long minor = toMinor(
                        resource.path("amount").path("value").asText("0"));

                String currency = resource.path("amount")
                        .path("currency_code").asText("USD");

                String paypalStatus = resource.path("status").asText();

                if (!"COMPLETED".equals(paypalStatus)) {
                    throw new IllegalStateException(
                            "PayPal refund is not completed: " + paypalStatus);
                }

                // Find the matching refund request.
                List<Long> requestIds = jdbc.query(
                        """
                        SELECT id
                        FROM refund_requests
                        WHERE paypal_refund_id = ?
                        FOR UPDATE
                        """,
                        (rs, rowNum) -> rs.getLong("id"),
                        refundId
                );

                if (requestIds.isEmpty()) {
                    throw new IllegalStateException(
                            "No refund request found for PayPal refund: " + refundId);
                }

                Long requestId = requestIds.get(0);

                // Confirm the amount and currency match our request.
               Integer matchingRequest = jdbc.queryForObject(
        """
        SELECT COUNT(*)
        FROM refund_requests
        WHERE id = ?
          AND amount_minor = ?
          AND currency = ?
        """,
        Integer.class,
        requestId, minor, currency
);

if (matchingRequest == null || matchingRequest != 1) {
    throw new IllegalStateException(
            "Refund amount or currency does not match request");
}

                // Record the refund reversal exactly once.
                ledger.post(
                        "refund:" + refundId,
                        "PayPal refund " + refundId,
                        List.of(
                                new LedgerService.Line(
                                        "SALES_REVENUE", 'D', minor, currency),
                                new LedgerService.Line(
                                        "PAYPAL_CLEARING", 'C', minor, currency)
                        )
                );

                // Update the request after ledger posting.
                jdbc.update(
                        """
                        UPDATE refund_requests
                        SET status = 'COMPLETED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                        requestId
                );
            }

            default -> {
                // Other event types are stored but not processed.
            }
        }

        return ResponseEntity.ok("processed");
    }

    @GetMapping("/api/ledger/status")
    public Map<String, Object> status() {
        return Map.of("balanced", ledger.isBalanced());
    }

    private static long toMinor(String amount) {
        return new BigDecimal(amount)
                .movePointRight(2)
                .longValueExact();
    }
}
