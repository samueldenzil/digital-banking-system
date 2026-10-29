package com.example.transaction_service.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionStatus;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // 1. Correlate SAGA events (Kafka events reference this unique ID)
    Optional<Transaction> findByReferenceNumber(String referenceNumber);

    // 2. Prevent duplicate transfers (Idempotency)
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    // 3. Statement / Transaction history for an account (sent or received)
    List<Transaction> findBySourceAccountNumberOrderByCreatedAtDesc(String sourceAccountNumber);

    List<Transaction> findByTargetAccountNumberOrderByCreatedAtDesc(String targetAccountNumber);

    // 4. Find all transactions involving an account (sent OR received)
    List<Transaction> findBySourceAccountNumberOrTargetAccountNumberOrderByCreatedAtDesc(String sourceAccountNumber,
            String targetAccountNumber);

    // 5. Monitor / retry pending SAGA transactions
    List<Transaction> findByStatus(TransactionStatus status);
}
