package com.example.notification_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionCompletedEvent(
        UUID transactionId,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        Instant timestamp) {
}
