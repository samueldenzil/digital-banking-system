package com.example.payment_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCompletedEvent(
                UUID paymentId,
                UUID transactionId,
                String accountNumber,
                BigDecimal amount,
                String currency,
                String gatewayPaymentId,
                Instant timestamp) {
}
