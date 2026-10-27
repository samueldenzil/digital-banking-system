package com.example.account_service.controller;

import com.example.account_service.dto.AccountResponse;
import com.example.account_service.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        // TODO
        return null;
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountNumber) {
        // TODO
        return null;
    }

    @PutMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> blockAccount(@PathVariable String accountNumber) {
        // TODO
        return null;
    }

    /**
     * SAGA Step 1 - Deduct Balance
     * Called by Transaction Service when transfer is initiated
     */
    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<AccountResponse> deductBalance(@PathVariable String accountNumber) {
        // TODO
        return null;
    }

    /**
     * SAGA Step 4 - Compensating transaction endpoint
     * CALLED BY TRANSACTION SERVICE in TWO SCENARIOS:
     * 1. Fraud detection -> refund sender (undo step 1)
     * 2. Transaction completed -> Credit receiver
     */
    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<AccountResponse> creditBalance(@PathVariable String accountNumber) {
        // TODO
        return null;
    }

}
