package com.example.transaction_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FraudAlertEvent(
        UUID transactionId,
        String accountNumber,
        BigDecimal amount,
        String reason,
        Instant timestamp
) {
}
