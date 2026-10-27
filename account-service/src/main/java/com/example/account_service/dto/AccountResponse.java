package com.example.account_service.dto;

import com.example.account_service.entity.Account;
import com.example.account_service.entity.AccountStatus;
import com.example.account_service.entity.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        Long version,
        String accountNumber,
        String customerId,
        String accountHolderName,
        String email,
        String phone,
        BigDecimal balance,
        BigDecimal dailyTransactionLimit,
        String currency,
        AccountStatus status,
        AccountType type,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AccountResponse fromEntity(Account account) {
        if (account == null) {
            return null;
        }

        return new AccountResponse(
                account.getId(),
                account.getVersion(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getAccountHolderName(),
                account.getEmail(),
                account.getPhone(),
                account.getBalance(),
                account.getDailyTransactionLimit(),
                account.getCurrency(),
                account.getStatus(),
                account.getType(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}