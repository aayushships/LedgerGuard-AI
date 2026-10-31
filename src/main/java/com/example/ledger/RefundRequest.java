package com.example.ledger;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capture_id", nullable = false)
    private String captureId;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "paypal_refund_id", unique = true)
    private String paypalRefundId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCaptureId() { return captureId; }
    public void setCaptureId(String captureId) { this.captureId = captureId; }

    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaypalRefundId() { return paypalRefundId; }
    public void setPaypalRefundId(String paypalRefundId) {
        this.paypalRefundId = paypalRefundId;
    }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}