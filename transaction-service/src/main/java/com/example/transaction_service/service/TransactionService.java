package com.example.transaction_service.service;

import java.util.List;
import java.util.UUID;

import com.example.transaction_service.dto.OtpVerificationRequest;
import com.example.transaction_service.dto.TransactionRequest;
import com.example.transaction_service.dto.TransactionResponse;

public interface TransactionService {
    TransactionResponse createTransaction(TransactionRequest request);

    TransactionResponse getTransactionById(UUID id);

    List<TransactionResponse> getTransactionsByAccountNumber(String accountNumber);

    TransactionResponse verifyOtp(OtpVerificationRequest request);
}
