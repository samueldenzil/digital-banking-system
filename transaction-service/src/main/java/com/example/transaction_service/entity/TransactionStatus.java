package com.example.transaction_service.entity;

public enum TransactionStatus {
    PENDING, // Transaction created, SAGA is beginning
    PROCESSING, // Sender balance has been debited; awaiting fraud check
    SUCCESS, // Receiver credited, transfer finalized
    FAILED, // Step 1 failed (e.g. sender had insufficient balance or inactive account).
    REJECTED, // Fraud detection flagged the transaction as suspicious.
    REFUNDED // Compensating transaction executed (funds refunded to sender).
}
