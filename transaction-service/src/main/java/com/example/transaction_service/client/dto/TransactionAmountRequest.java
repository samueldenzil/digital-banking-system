package com.example.transaction_service.client.dto;

import java.math.BigDecimal;

public record TransactionAmountRequest(BigDecimal amount) {
}
