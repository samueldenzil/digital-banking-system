package com.example.transaction_service.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        String customerId,
        BigDecimal balance,
        String currency,
        String status
) {
}
