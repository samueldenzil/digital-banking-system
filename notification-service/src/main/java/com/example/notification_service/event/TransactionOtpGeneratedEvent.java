package com.example.notification_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionOtpGeneratedEvent(
        UUID transactionId,
        String accountNumber,
        String reason,
        String otp,
        BigDecimal amount,
        Instant timestamp
) {}
