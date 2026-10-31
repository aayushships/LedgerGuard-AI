package com.example.ledger;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundRequestService {
    private final LedgerService ledgerService;

    private final PayPalClient payPalClient;
    private final RefundRequestRepository refundRequestRepository;

  public RefundRequestService(
        RefundRequestRepository refundRequestRepository,
        PayPalClient payPalClient,
        LedgerService ledgerService) {

    this.refundRequestRepository = refundRequestRepository;
    this.payPalClient = payPalClient;
    this.ledgerService = ledgerService;
}
    @Transactional
    public RefundRequest createRefundRequest(
            String captureId,
            Long amountMinor,
            String currency,
            String reason) {

        if (captureId == null || captureId.isBlank()) {
            throw new IllegalArgumentException("Capture ID is required");
        }

        if (amountMinor == null || amountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }

        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("Currency must contain 3 characters");
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Refund reason is required");
        }

        RefundRequest request = new RefundRequest();

        request.setCaptureId(captureId);
        request.setAmountMinor(amountMinor);
        request.setCurrency(currency.toUpperCase());
        request.setReason(reason);
        request.setStatus("PENDING");

        ledgerService.lockPayment(captureId);
        validateRefundAmount(request);

        return refundRequestRepository.save(request);
    }

    public java.util.List<RefundRequest> getAllRefundRequests() {
    return refundRequestRepository.findAll();
}

@Transactional
public RefundRequest approveRefundRequest(Long id) {

    RefundRequest request = refundRequestRepository.findById(id)
            .orElseThrow(() ->
                    new RuntimeException("Refund request not found"));

    if (!"PENDING".equals(request.getStatus())) {
        throw new IllegalStateException(
                "Only pending refund requests can be approved");
    }

    // Lock the original payment
    ledgerService.lockPayment(request.getCaptureId());

    // Recheck the available refundable amount
    validateRefundAmount(request);

    request.setStatus("APPROVED");

    return refundRequestRepository.save(request);
}

@Transactional
public RefundRequest processApprovedRefund(Long id) {

    RefundRequest request = refundRequestRepository.findById(id)
            .orElseThrow(() ->
                    new RuntimeException("Refund request not found"));

    if (!"APPROVED".equals(request.getStatus())) {
        throw new IllegalStateException(
                "Only approved refunds can be processed");
    }

    if (request.getPaypalRefundId() != null) {
        throw new IllegalStateException(
                "This refund has already been submitted to PayPal");
    }

    // Lock the original payment
    ledgerService.lockPayment(request.getCaptureId());

    // Recheck refundable balance
    validateRefundAmount(request);

    String amount = java.math.BigDecimal.valueOf(
            request.getAmountMinor(), 2
    ).toPlainString();

    String requestId = "refund-request-" + request.getId();

    var response = payPalClient.refundCapture(
            request.getCaptureId(),
            amount,
            request.getCurrency(),
            requestId
    );

    String refundId = response.path("id").asText();
    String paypalStatus = response.path("status").asText();

    if (refundId.isBlank()) {
        throw new IllegalStateException(
                "PayPal did not return a refund ID");
    }

    request.setPaypalRefundId(refundId);

    // Wait for PayPal's webhook to confirm completion
    request.setStatus("PROCESSING");

    return refundRequestRepository.save(request);
}

private void validateRefundAmount(RefundRequest request) {

    Long originalAmount =
            ledgerService.getPaymentAmount(request.getCaptureId());

    if (originalAmount == null || originalAmount <= 0) {
        throw new IllegalArgumentException(
                "Original payment not found in ledger");
    }

    long alreadyRefunded = refundRequestRepository.findAll()
            .stream()
            .filter(r -> r.getCaptureId().equals(request.getCaptureId()))
            .filter(r -> "PENDING".equals(r.getStatus())
                    ||"COMPLETED".equals(r.getStatus())
                    || "APPROVED".equals(r.getStatus())
                    || "PROCESSING".equals(r.getStatus()))
                    
            .filter(r -> !r.getId().equals(request.getId()))
            .mapToLong(RefundRequest::getAmountMinor)
            .sum();

    if (request.getAmountMinor() > originalAmount - alreadyRefunded) {
        throw new IllegalArgumentException(
                "Refund amount exceeds the available refundable balance");
    }
}
}