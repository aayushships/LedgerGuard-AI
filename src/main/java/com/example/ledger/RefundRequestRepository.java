package com.example.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRequestRepository
        extends JpaRepository<RefundRequest, Long> {
}