package com.example.transaction_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event published when an OTP is generated for a flagged transaction,
 * requesting notification-service to deliver the OTP challenge to the customer.
 */
public record TransactionOtpGeneratedEvent(
        UUID transactionId,
        String accountNumber,
        String reason,
        String otp,
        BigDecimal amount,
        Instant timestamp
) {}

