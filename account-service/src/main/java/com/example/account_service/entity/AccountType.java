package com.example.account_service.entity;

import java.math.BigDecimal;

/**
 * Account types supported by the banking platform with their default daily transaction limits.
 */
public enum AccountType {
    SAVINGS(BigDecimal.valueOf(100_000)),
    CURRENT(BigDecimal.valueOf(500_000)),
    FIXED_DEPOSIT(BigDecimal.ZERO);

    private final BigDecimal defaultDailyLimit;

    AccountType(BigDecimal defaultDailyLimit) {
        this.defaultDailyLimit = defaultDailyLimit;
    }

    public BigDecimal getDefaultDailyLimit() {
        return defaultDailyLimit;
    }
}
