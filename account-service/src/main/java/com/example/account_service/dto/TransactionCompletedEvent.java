package com.example.account_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionCompletedEvent(
    String transactionId,
    String sourceAccountNumber,
    String targetAccountNumber,
    BigDecimal amount,
    LocalDateTime timestamp
) {}
