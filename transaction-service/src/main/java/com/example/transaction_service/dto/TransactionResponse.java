package com.example.transaction_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionStatus;
import com.example.transaction_service.entity.TransactionType;

public record TransactionResponse(
        UUID id,
        String referenceNumber,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        String currency,
        TransactionType type,
        TransactionStatus status,
        String description,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {

    public static TransactionResponse fromEntity(Transaction transaction) {
        if (transaction == null) {
            return null;
        }
        
        return new TransactionResponse(
                transaction.getId(),
                transaction.getReferenceNumber(),
                transaction.getSourceAccountNumber(),
                transaction.getTargetAccountNumber(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getDescription(),
                transaction.getFailureReason(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt());
    }
}
