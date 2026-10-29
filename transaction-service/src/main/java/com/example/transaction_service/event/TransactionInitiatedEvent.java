package com.example.transaction_service.event;

import com.example.transaction_service.entity.TransactionType;

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
        TransactionType type,
        Instant timestamp
) {
}
