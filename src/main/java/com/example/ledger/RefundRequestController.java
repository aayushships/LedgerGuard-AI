package com.example.ledger;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/refunds")
public class RefundRequestController {

    private final RefundRequestService refundRequestService;

    public RefundRequestController(
            RefundRequestService refundRequestService) {
        this.refundRequestService = refundRequestService;
    }

    @PostMapping("/request")
    public ResponseEntity<RefundRequest> createRefundRequest(
            @RequestBody RefundRequest request) {

        RefundRequest savedRequest =
                refundRequestService.createRefundRequest(
                        request.getCaptureId(),
                        request.getAmountMinor(),
                        request.getCurrency(),
                        request.getReason()
                );

        return ResponseEntity.ok(savedRequest);
    }

    @GetMapping
public ResponseEntity<java.util.List<RefundRequest>> getAllRefundRequests() {
    return ResponseEntity.ok(
            refundRequestService.getAllRefundRequests()
    );
}

@PostMapping("/{id}/approve")
public ResponseEntity<RefundRequest> approveRefundRequest(
        @PathVariable Long id) {

    RefundRequest approvedRequest =
            refundRequestService.approveRefundRequest(id);

    return ResponseEntity.ok(approvedRequest);
}

@PostMapping("/{id}/process")
public ResponseEntity<RefundRequest> processRefund(
        @PathVariable Long id) {

    RefundRequest processedRequest =
            refundRequestService.processApprovedRefund(id);

    return ResponseEntity.ok(processedRequest);
}
}