package com.example.transaction_service.dto;

import java.math.BigDecimal;

import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransactionRequest(
        @NotBlank(message = "Source account number is required") String sourceAccountNumber,

        @NotBlank(message = "Target account number is required") String targetAccountNumber,

        @NotNull(message = "Amount is required") @Positive(message = "Transfer amount must be greater than zero") BigDecimal amount,

        @NotBlank(message = "Currency is required") @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code") String currency,

        @Size(max = 255, message = "Description must not exceed 255 characters") String description,

        String idempotencyKey,

        TransactionType type) {

    public TransactionRequest {
        if (type == null) {
            type = TransactionType.TRANSFER;
        }
    }

    public TransactionRequest(
            String sourceAccountNumber,
            String targetAccountNumber,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey) {
        this(sourceAccountNumber, targetAccountNumber, amount, currency, description, idempotencyKey, TransactionType.TRANSFER);
    }

    public Transaction toEntity() {
        Transaction transaction = new Transaction();

        transaction.setSourceAccountNumber(this.sourceAccountNumber);
        transaction.setTargetAccountNumber(this.targetAccountNumber);
        transaction.setAmount(this.amount);
        transaction.setCurrency(this.currency);
        transaction.setDescription(this.description);
        transaction.setIdempotencyKey(this.idempotencyKey);
        transaction.setType(this.type != null ? this.type : TransactionType.TRANSFER);

        return transaction;
    }
}
