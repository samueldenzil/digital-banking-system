package com.example.account_service.service.impl;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.account_service.dto.AccountResponse;
import com.example.account_service.dto.CreateAccountRequest;
import com.example.account_service.entity.Account;
import com.example.account_service.entity.AccountStatus;
import com.example.account_service.entity.AccountType;
import com.example.account_service.exception.AccountNotFoundException;
import com.example.account_service.repository.AccountRepository;
import com.example.account_service.service.AccountService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    private static SecureRandom secureRandom = new SecureRandom();

    @Override
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
    public AccountResponse debitAccount(String accountNumber, BigDecimal amount) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'debitAccount'");
    }

    @Override
    public AccountResponse creditAccount(String accountNumber, BigDecimal amount) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'creditAccount'");
    }

    @Override
    public void blockAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new AccountNotFoundException("Account not found for account number: " + accountNumber));

        account.setStatus(AccountStatus.FROZEN);
        accountRepository.save(account);
    }

    @Override
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
            long random = secureRandom.nextLong(1_000_000_000_000L);
            accountNumber = String.format("%012d", random);
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
