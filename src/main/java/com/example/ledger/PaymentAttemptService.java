
package com.example.ledger;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Service
public class PaymentAttemptService {

    private final PaymentAttemptRepository repository;

    public PaymentAttemptService(
            PaymentAttemptRepository repository) {
        this.repository = repository;
    }

    public PaymentAttemptRepository.PaymentAttempt startOrReuse(
            long userId,
            BigDecimal amount,
            String currency) {

        if (userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid user ID");
        }

        if (amount == null || amount.signum() <= 0
                || amount.scale() > 2) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid payment amount");
        }

        var existing = repository.findActiveByUserId(userId);

        if (existing.isPresent()) {

            var attempt = existing.get();

            boolean sameAmount =
                    attempt.amount().compareTo(amount) == 0;

            boolean sameCurrency =
                    attempt.currency().equals(currency);

            if (sameAmount && sameCurrency) {
                return attempt;
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "You already have an active payment. Complete or cancel it first."
            );
        }

        try {
            return repository.create(userId, amount, currency);

        } catch (DataIntegrityViolationException ex) {

            var concurrentAttempt =
                    repository.findActiveByUserId(userId);

            if (concurrentAttempt.isPresent()) {

                var attempt = concurrentAttempt.get();

                if (attempt.amount().compareTo(amount) == 0
                        && attempt.currency().equals(currency)) {
                    return attempt;
                }

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "You already have a different active payment."
                );
            }

            throw ex;
        }
    }

    public void savePayPalOrder(
            long attemptId,
            String paypalOrderId,
            String approveUrl) {

        repository.savePayPalOrder(
                attemptId,
                paypalOrderId,
                approveUrl
        );
    }

    public void recordCapture(
            String paypalOrderId,
            String captureId) {

        repository.recordCapture(
                paypalOrderId,
                captureId
        );
    }
}
