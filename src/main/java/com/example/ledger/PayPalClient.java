package com.example.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Talks to PayPal's sandbox REST API. */
@Component
public class PayPalClient {

    public record OrderCreated(String orderId, String approveUrl) {}

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String webhookId;
    private final String appBaseUrl;

    private String cachedToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public PayPalClient(@Value("${paypal.base-url}") String baseUrl,
                        @Value("${paypal.client-id}") String clientId,
                        @Value("${paypal.client-secret}") String clientSecret,
                        @Value("${paypal.webhook-id}") String webhookId,
                        @Value("${app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.webhookId = webhookId;
        this.appBaseUrl = appBaseUrl;
    }

    /** Signature checking only turns on once you have set a real webhook id. */
    public boolean webhookConfigured() {
        return !"changeme".equals(webhookId);
    }

    private synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        JsonNode res = http.post().uri("/v1/oauth2/token")
                .headers(h -> h.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class);
        cachedToken = res.path("access_token").asText();
        // refresh a minute early
        tokenExpiry = Instant.now().plusSeconds(Math.max(res.path("expires_in").asLong(300) - 60, 30));
        return cachedToken;
    }

    /**
     * Creates an order. The requestId goes in the PayPal-Request-Id header:
     * if the same id is sent again, PayPal returns the SAME order instead of making a second one.
     */
    public OrderCreated createOrder(String amount, String currency, String requestId) {
        Map<String, Object> body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "amount", Map.of("currency_code", currency, "value", amount))),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "return_url", appBaseUrl + "/api/orders/return",
                        "cancel_url", appBaseUrl + "/api/orders/cancel",
                        "user_action", "PAY_NOW"))));

        JsonNode res = http.post().uri("/v2/checkout/orders")
                .headers(h -> {
                    h.setBearerAuth(token());
                    h.set("PayPal-Request-Id", requestId);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);

        String approveUrl = null;
        for (JsonNode link : res.path("links")) {
            String rel = link.path("rel").asText();
            if (rel.equals("approve") || rel.equals("payer-action")) {
                approveUrl = link.path("href").asText();
            }
        }
        return new OrderCreated(res.path("id").asText(), approveUrl);
    }

    /** Captures (takes) the money after the buyer approved. Safe to retry: same request id per order. */
    public JsonNode captureOrder(String orderId) {
        return http.post().uri("/v2/checkout/orders/{id}/capture", orderId)
                .headers(h -> {
                    h.setBearerAuth(token());
                    h.set("PayPal-Request-Id", "capture-" + orderId);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve().body(JsonNode.class);
    }
    /**
 * Refunds a captured PayPal payment.
 */
public JsonNode refundCapture(
        String captureId,
        String amount,
        String currency,
        String requestId) {

    Map<String, Object> body = Map.of(
            "amount", Map.of(
                    "value", amount,
                    "currency_code", currency
            )
    );

    return http.post()
            .uri("/v2/payments/captures/{id}/refund", captureId)
            .headers(h -> {
                h.setBearerAuth(token());
                h.set("PayPal-Request-Id", requestId);
            })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
}
    

    /** Asks PayPal whether a webhook really came from PayPal. */
    public boolean verifyWebhook(HttpHeaders headers, JsonNode event) {
        Map<String, Object> body = new HashMap<>();
        body.put("auth_algo", headers.getFirst("PAYPAL-AUTH-ALGO"));
        body.put("cert_url", headers.getFirst("PAYPAL-CERT-URL"));
        body.put("transmission_id", headers.getFirst("PAYPAL-TRANSMISSION-ID"));
        body.put("transmission_sig", headers.getFirst("PAYPAL-TRANSMISSION-SIG"));
        body.put("transmission_time", headers.getFirst("PAYPAL-TRANSMISSION-TIME"));
        body.put("webhook_id", webhookId);
        body.put("webhook_event", event);

        JsonNode res = http.post().uri("/v1/notifications/verify-webhook-signature")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
        return "SUCCESS".equals(res.path("verification_status").asText());
    }
}