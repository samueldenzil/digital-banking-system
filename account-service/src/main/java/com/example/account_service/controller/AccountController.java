package com.example.account_service.controller;

import com.example.account_service.dto.AccountResponse;
import com.example.account_service.dto.CreateAccountRequest;
import com.example.account_service.dto.TransactionAmountRequest;
import com.example.account_service.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountNumber) {
        AccountResponse response = accountService.getAccountByNumber(accountNumber);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<AccountResponse>> getAccountsByCustomerId(@PathVariable String customerId) {
        List<AccountResponse> accounts = accountService.getAccountsByCustomerId(customerId);
        return ResponseEntity.ok(accounts);
    }

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<Void> blockAccount(@PathVariable String accountNumber) {
        accountService.blockAccount(accountNumber);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{accountNumber}/unblock")
    public ResponseEntity<Void> unblockAccount(@PathVariable String accountNumber) {
        accountService.unblockAccount(accountNumber);
        return ResponseEntity.noContent().build();
    }

    /**
     * SAGA Step 1 - Deduct Balance
     * Called by Transaction Service when transfer is initiated
     */
    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<AccountResponse> deductBalance(
            @PathVariable String accountNumber,
            @Valid @RequestBody TransactionAmountRequest request) {
        AccountResponse response = accountService.debitAccount(accountNumber, request.amount());
        return ResponseEntity.ok(response);
    }

    /**
     * SAGA Step 4 - Compensating transaction endpoint
     * CALLED BY TRANSACTION SERVICE in TWO SCENARIOS:
     * 1. Fraud detection -> refund sender (undo step 1)
     * 2. Transaction completed -> Credit receiver
     */
    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<AccountResponse> creditBalance(
            @PathVariable String accountNumber,
            @Valid @RequestBody TransactionAmountRequest request) {
        AccountResponse response = accountService.creditAccount(accountNumber, request.amount());
        return ResponseEntity.ok(response);
    }

}
