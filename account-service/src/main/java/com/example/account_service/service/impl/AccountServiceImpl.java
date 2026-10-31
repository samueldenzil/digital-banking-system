package com.example.account_service.service.impl;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.account_service.dto.AccountResponse;
import com.example.account_service.dto.CreateAccountRequest;
import com.example.account_service.entity.Account;
import com.example.account_service.entity.AccountStatus;
import com.example.account_service.exception.AccountInactiveException;
import com.example.account_service.exception.AccountNotFoundException;
import com.example.account_service.exception.InsufficientBalanceException;
import com.example.account_service.repository.AccountRepository;
import com.example.account_service.service.AccountService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        List<Account> existingAccounts = accountRepository.findByEmail(request.getEmail());

        Account account = request.toEntity();

        String customerId = existingAccounts.stream().findFirst().map(Account::getCustomerId)
                .orElse(generateCustomerId());
        account.setAccountNumber(generateAccountNumber());
        account.setCustomerId(customerId);

        // Assign default daily transaction limit based on account type domain
        // specifications
        BigDecimal dailyLimit = account.getType() != null
                ? account.getType().getDefaultDailyLimit()
                : BigDecimal.valueOf(100_000);
        account.setDailyTransactionLimit(dailyLimit);

        Account savedAccount = accountRepository.save(account);

        return AccountResponse.fromEntity(savedAccount);
    }

    @Override
    public AccountResponse getAccountByNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        return AccountResponse.fromEntity(account);
    }

    @Override
    public List<AccountResponse> getAccountsByCustomerId(String customerId) {
        List<Account> accounts = accountRepository.findByCustomerId(customerId);

        return accounts.stream().map(AccountResponse::fromEntity).toList();
    }

    @Override
    @Transactional
    public AccountResponse debitAccount(String accountNumber, BigDecimal amount) {
        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber).orElseThrow(
                () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Account is not active: " + accountNumber);
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be greater than zero: " + amount);
        }

        // TODO: Verify cumulative daily transaction limit against
        // account.getDailyTransactionLimit()

        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for account number: " + accountNumber);
        }

        account.setBalance(account.getBalance().subtract(amount));

        Account savedAccount = accountRepository.save(account);

        return AccountResponse.fromEntity(savedAccount);
    }

    @Override
    @Transactional
    public AccountResponse creditAccount(String accountNumber, BigDecimal amount) {
        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(
                        () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        // In banking, Post-No-Debit (FROZEN) accounts can still receive incoming
        // credits/deposits;
        // only CLOSED accounts strictly reject deposits.
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountInactiveException("Cannot credit closed account: " + accountNumber);
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be greater than zero: " + amount);
        }

        account.setBalance(account.getBalance().add(amount));

        Account savedAccount = accountRepository.save(account);
        log.info("Credited {} to account {} (Status: {})", amount, accountNumber, account.getStatus());

        return AccountResponse.fromEntity(savedAccount);
    }

    @Override
    @Transactional
    public AccountResponse refundAccount(String accountNumber, BigDecimal amount) {
        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(
                        () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountInactiveException("Cannot refund closed account: " + accountNumber);
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero: " + amount);
        }

        account.setBalance(account.getBalance().add(amount));

        Account savedAccount = accountRepository.save(account);
        log.info("SAGA compensation refund: credited {} back to account {} (Status: {})",
                amount, accountNumber, account.getStatus());

        return AccountResponse.fromEntity(savedAccount);
    }

    @Override
    @Transactional
    public void blockAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        account.setStatus(AccountStatus.FROZEN);
        accountRepository.save(account);
    }

    @Override
    @Transactional
    public void unblockAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
    }

    /**
     * Generates a unique 12-digit account number.
     * 
     * @return
     */
    private String generateAccountNumber() {
        String accountNumber;

        do {
            long random = 100_000_000_000L + SECURE_RANDOM.nextLong(900_000_000_000L);
            accountNumber = String.valueOf(random);
        } while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    /**
     * Generates a unique customer ID.
     * 
     * @return
     */
    private String generateCustomerId() {
        String customerId;

        do {
            customerId = "CUST_" + UUID.randomUUID().toString();
        } while (accountRepository.existsByCustomerId(customerId));

        return customerId;
    }
}
