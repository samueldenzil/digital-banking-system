package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentRequest(
        @NotBlank String accountNumber,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull UUID transactionId,
        @NotBlank String paymentMethod) {
}
