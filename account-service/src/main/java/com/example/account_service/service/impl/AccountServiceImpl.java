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
import com.example.account_service.entity.AccountType;
import com.example.account_service.exception.AccountInactiveException;
import com.example.account_service.exception.AccountNotFoundException;
import com.example.account_service.exception.InsufficientBalanceException;
import com.example.account_service.repository.AccountRepository;
import com.example.account_service.service.AccountService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    private static SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        List<Account> existingAccounts = accountRepository.findByEmail(request.getEmail());

        Account account = request.toEntity();

        String customerId = existingAccounts.stream().findFirst().map(Account::getCustomerId)
                .orElse(generateCustomerId());
        account.setAccountNumber(generateAccountNumber());
        account.setCustomerId(customerId);
        account.setDailyTransactionLimit(
                account.getType() == AccountType.SAVINGS ? BigDecimal.valueOf(100000) : BigDecimal.valueOf(500000));

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

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException("Account is not active: " + accountNumber);
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be greater than zero: " + amount);
        }

        account.setBalance(account.getBalance().add(amount));

        Account savedAccount = accountRepository.save(account);

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
            long random = 100_000_000_000L + secureRandom.nextLong(900_000_000_000L);
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
