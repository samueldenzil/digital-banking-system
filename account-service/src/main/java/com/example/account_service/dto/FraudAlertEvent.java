package com.example.account_service.dto;

import java.time.LocalDateTime;

public record FraudAlertEvent(
        String transactionId,
        String accountNumber,
        String reason,
        LocalDateTime timestamp) {
}