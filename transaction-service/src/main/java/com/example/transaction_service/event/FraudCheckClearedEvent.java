package com.example.transaction_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event received from fraud-detection-service when a transaction
 * is cleared of suspicion.
 */
public record FraudCheckClearedEvent(
        UUID transactionId,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        Instant timestamp
) {}

