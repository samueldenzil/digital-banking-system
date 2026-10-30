package com.example.fraud_detection_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published by fraud-detection-service when a transaction
 * passes all fraud checks and is cleared for execution.
 */
public record FraudCheckClearedEvent(
        UUID transactionId,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        Instant timestamp
) {}

