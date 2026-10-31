package com.example.payment_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;

public record PaymentResponse(
        UUID id,
        UUID transactionId,
        String accountNumber,
        BigDecimal amount,
        String currency,
        String stripePaymentIntentId,
        String stripeClientSecret,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {

    public static PaymentResponse fromEntity(Payment payment) {
        if (payment == null) {
            return null;
        }

        return new PaymentResponse(
                payment.getId(),
                payment.getTransactionId(),
                payment.getAccountNumber(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStripePaymentIntentId(),
                payment.getStripeClientSecret(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getCompletedAt());
    }
}
