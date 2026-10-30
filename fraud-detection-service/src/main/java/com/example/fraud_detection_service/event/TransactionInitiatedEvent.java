package com.example.fraud_detection_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionInitiatedEvent(
        UUID transactionId,
        String referenceNumber,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        String currency,
        String type,
        Instant timestamp
) {}
