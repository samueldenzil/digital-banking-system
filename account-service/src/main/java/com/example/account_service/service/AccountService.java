package com.example.account_service.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.account_service.dto.AccountResponse;
import com.example.account_service.dto.CreateAccountRequest;

public interface AccountService {

    AccountResponse createAccount(CreateAccountRequest request);

    AccountResponse getAccountByNumber(String accountNumber);

    List<AccountResponse> getAccountsByCustomerId(String customerId);

    /**
     * Block account - called by Fraud detection service via Kafka
     * 
     * @param accountNumber
     */
    void blockAccount(String accountNumber);

    void unblockAccount(String accountNumber);

    /**
     * Deduct balance from sender account. Called by Transaction Service
     * 
     * @param accountNumber
     * @param amount
     * @return
     */
    AccountResponse debitAccount(String accountNumber, BigDecimal amount);

    AccountResponse creditAccount(String accountNumber, BigDecimal amount);
}
