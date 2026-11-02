package com.example.notification_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentFailedEvent(
        UUID paymentId,
        UUID transactionId,
        String accountNumber,
        BigDecimal amount,
        String currency,
        String gatewayPaymentId,
        String failureReason,
        Instant timestamp) {

}
