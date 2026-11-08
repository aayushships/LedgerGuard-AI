package com.example.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;  

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Checkout flow:
 *  1. POST /api/orders          -> returns a PayPal approve link
 *  2. Buyer opens the link and approves with the sandbox buyer account
 *  3. PayPal redirects to /api/orders/return -> we capture the money
 *  4. PayPal sends the PAYMENT.CAPTURE.COMPLETED webhook -> the ledger is written there (only there)
 */
@RestController
public class OrderController {

   public record CreateOrderRequest(

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

    public OrderController(PayPalClient paypal) {
        this.paypal = paypal;
    }

    @PostMapping("/api/orders")
    public Map<String, String> create(
        @Valid @RequestBody CreateOrderRequest req,
        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        // Same Idempotency-Key sent twice = same PayPal order, never two.
        String requestId = key != null ? key : UUID.randomUUID().toString();
        String currency = req.currency();
        PayPalClient.OrderCreated o = paypal.createOrder(req.amount(), currency, requestId);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("orderId", o.orderId());
        out.put("approveUrl", o.approveUrl());
        return out;
    }

    @GetMapping("/api/orders/return")
    public Map<String, Object> returned(@RequestParam("token") String orderId) {
        JsonNode res = paypal.captureOrder(orderId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("status", res.path("status").asText());
        out.put("captureId", res.path("purchase_units").path(0).path("payments")
                .path("captures").path(0).path("id").asText(null));
        out.put("note", "Ledger entry is written when the PayPal webhook arrives.");
        return out;
    }

    @GetMapping("/api/orders/cancel")
    public Map<String, String> cancelled() {
        return Map.of("status", "cancelled by buyer");
    }

    /** Show PayPal's own error text so problems are easy to read. */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<String> paypalError(RestClientResponseException e) {
        return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
    }
}