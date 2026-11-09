
package com.example.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class OrderController {

    public record CreateOrderRequest(

            @NotNull(message = "User ID is required")
            @Positive(message = "User ID must be positive")
            Long userId,

            @NotBlank(message = "Amount is required")
            @Pattern(
                    regexp = "^(?=.*[1-9])\\d+(\\.\\d{1,2})?$",
                    message = "Amount must be positive and have at most 2 decimal places"
            )
            String amount,

            @NotBlank(message = "Currency is required")
            @Pattern(
                    regexp = "^[A-Z]{3}$",
                    message = "Currency must be a 3-letter uppercase code"
            )
            String currency
    ) {}

    private final PayPalClient paypal;
    private final PaymentAttemptService paymentAttemptService;

    public OrderController(
            PayPalClient paypal,
            PaymentAttemptService paymentAttemptService) {

        this.paypal = paypal;
        this.paymentAttemptService = paymentAttemptService;
    }

    @PostMapping("/api/orders")
    public Map<String, Object> create(
            @Valid @RequestBody CreateOrderRequest req) {

        BigDecimal amount = new BigDecimal(req.amount());

        var attempt = paymentAttemptService.startOrReuse(
                req.userId(),
                amount,
                req.currency()
        );

        // Reuse an existing PayPal order.
        if (attempt.paypalOrderId() != null) {

            if ("APPROVAL_PENDING".equals(attempt.status())
                    || "ORDER_CREATED".equals(attempt.status())) {

                return orderResponse(
                        attempt.id(),
                        attempt.paypalOrderId(),
                        attempt.approveUrl(),
                        attempt.status()
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your payment is already being processed."
            );
        }

        if (!"CREATED".equals(attempt.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This payment attempt cannot create a new PayPal order."
            );
        }

        // Stable PayPal idempotency key.
        String requestId = "payment-attempt-" + attempt.id();

        PayPalClient.OrderCreated paypalOrder = paypal.createOrder(
                req.amount(),
                req.currency(),
                requestId
        );

        paymentAttemptService.savePayPalOrder(
                attempt.id(),
                paypalOrder.orderId(),
                paypalOrder.approveUrl()
        );

        return orderResponse(
                attempt.id(),
                paypalOrder.orderId(),
                paypalOrder.approveUrl(),
                "APPROVAL_PENDING"
        );
    }

    private Map<String, Object> orderResponse(
            long attemptId,
            String orderId,
            String approveUrl,
            String status) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentAttemptId", attemptId);
        response.put("orderId", orderId);
        response.put("approveUrl", approveUrl);
        response.put("status", status);

        return response;
    }

    @GetMapping("/api/orders/return")
    public Map<String, Object> returned(
            @RequestParam("token") String orderId) {

        // Retrieve the latest PayPal order state.
        JsonNode order = paypal.getOrderDetails(orderId);

        String orderStatus = order.path("status").asText();

        // Already captured: recover the existing capture ID.
        if ("COMPLETED".equals(orderStatus)) {

            JsonNode captures = order.path("purchase_units")
                    .path(0)
                    .path("payments")
                    .path("captures");

            if (captures.isArray() && !captures.isEmpty()) {

                JsonNode capture = captures.get(0);

                String captureId = capture.path("id").asText();
                String captureStatus = capture.path("status").asText();

                if ("COMPLETED".equals(captureStatus)) {
                    paymentAttemptService.recordCapture(
                            orderId, captureId);
                }

                return paymentResponse(
                        orderId,
                        captureStatus,
                        captureId,
                        "Existing PayPal capture retrieved."
                );
            }
        }

        // Capture only when PayPal confirms buyer approval.
        if ("APPROVED".equals(orderStatus)) {

            JsonNode result = paypal.captureOrder(orderId);

            String captureStatus = result.path("status").asText();

            JsonNode captures = result.path("purchase_units")
                    .path(0)
                    .path("payments")
                    .path("captures");

            String captureId =
                    captures.isArray() && !captures.isEmpty()
                            ? captures.get(0).path("id").asText(null)
                            : null;

            if ("COMPLETED".equals(captureStatus)
                    && captureId != null) {

                paymentAttemptService.recordCapture(
                        orderId, captureId);
            }

            return paymentResponse(
                    orderId,
                    captureStatus,
                    captureId,
                    "Capture response received. Waiting for webhook confirmation."
            );
        }

        return paymentResponse(
                orderId,
                orderStatus,
                null,
                "Order is not approved for capture."
        );
    }

    private Map<String, Object> paymentResponse(
            String orderId,
            String status,
            String captureId,
            String note) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("status", status);
        response.put("captureId", captureId);
        response.put("note", note);

        return response;
    }

    @GetMapping("/api/orders/cancel")
    public Map<String, String> cancelled() {
        return Map.of("status", "cancelled by buyer");
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<String> paypalError(
            RestClientResponseException e) {

        return ResponseEntity.status(e.getStatusCode())
                .body(e.getResponseBodyAsString());
    }
}
