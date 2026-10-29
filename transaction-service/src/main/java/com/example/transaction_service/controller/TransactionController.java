package com.example.transaction_service.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.transaction_service.dto.OtpVerificationRequest;
import com.example.transaction_service.dto.TransactionRequest;
import com.example.transaction_service.dto.TransactionResponse;
import com.example.transaction_service.service.TransactionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.createTransaction(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransactionById(@PathVariable UUID id) {
        return ResponseEntity.ok(transactionService.getTransactionById(id));
    }

    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByAccountNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(transactionService.getTransactionsByAccountNumber(accountNumber));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<TransactionResponse> verifyOtp(@Valid @RequestBody OtpVerificationRequest request) {
        return ResponseEntity.ok(transactionService.verifyOtp(request));
    }
}
