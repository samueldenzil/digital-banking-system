package com.example.transaction_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionRefundedEvent(
        UUID transactionId,
        String senderAccountNumber,
        BigDecimal amount,
        String reason,
        Instant timestamp) {

}
