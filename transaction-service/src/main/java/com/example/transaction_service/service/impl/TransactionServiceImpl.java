package com.example.transaction_service.service.impl;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.transaction_service.event.TransactionInitiatedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.transaction_service.client.AccountServiceClient;
import com.example.transaction_service.client.dto.AccountResponse;
import com.example.transaction_service.client.dto.TransactionAmountRequest;
import com.example.transaction_service.dto.OtpVerificationRequest;
import com.example.transaction_service.dto.TransactionRequest;
import com.example.transaction_service.dto.TransactionResponse;
import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionStatus;
import com.example.transaction_service.entity.TransactionType;
import com.example.transaction_service.repository.TransactionRepository;
import com.example.transaction_service.service.TransactionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core SAGA Orchestration Service for processing fund transfers.
 * Coordinates account validation, two-factor authentication (OTP via Redis),
 * and inter-service communication with Account Service via OpenFeign.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object>  kafkaTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";

    @Override
    public TransactionResponse createTransaction(TransactionRequest request) {
        // 1. Idempotency Check: Prevent duplicate transactions caused by client network retries
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                log.warn("Duplicate request detected for idempotency key: {}", request.idempotencyKey());
                return TransactionResponse.fromEntity(existing.get());
            }
        }

        // 2. Prevent self-transfers (cannot transfer money to the same account)
        if (request.sourceAccountNumber().equals(request.targetAccountNumber())) {
            throw new IllegalArgumentException("Source and target accounts cannot be the same");
        }

        // 3. Pre-flight Validation: Query Account Service via OpenFeign to verify accounts
        AccountResponse sourceAccount = accountServiceClient.getAccount(request.sourceAccountNumber());
        AccountResponse targetAccount = accountServiceClient.getAccount(request.targetAccountNumber());

        if (sourceAccount == null || targetAccount == null) {
            throw new IllegalArgumentException("Source or target account not found");
        }

        // 4. Currency Check: Ensure source and target accounts match the transfer currency
        if (!sourceAccount.currency().equalsIgnoreCase(request.currency()) ||
                !targetAccount.currency().equalsIgnoreCase(request.currency())) {
            throw new IllegalArgumentException("Currency mismatch between transfer and accounts");
        }

        // 5. Balance Check: Verify sender has enough funds before initiating transfer
        if (sourceAccount.balance().compareTo(request.amount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }

        // 6. Account Status: Both sender and recipient must be active
        if (!"ACTIVE".equalsIgnoreCase(sourceAccount.status()) || !"ACTIVE".equalsIgnoreCase(targetAccount.status())) {
            throw new IllegalArgumentException("Source or target account is not active");
        }

        // 7. Initialize Transaction Record in PENDING status
        Transaction transaction = request.toEntity();
        String referenceNumber = "TXN-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setReferenceNumber(referenceNumber);

        Transaction savedTransaction = transactionRepository.save(transaction);

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getReferenceNumber(),
                savedTransaction.getSourceAccountNumber(),
                savedTransaction.getTargetAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getCurrency(),
                savedTransaction.getType(),
                Instant.now()
        );

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, transaction.getId().toString(), event);

        // 8. Generate a 6-digit OTP and cache in Redis with a 5-minute expiration (TTL)
        String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        log.info("Generated OTP for transaction {}: {}", savedTransaction.getId(), otp);
        redisTemplate.opsForValue().set("otp:" + savedTransaction.getId(), otp, 5, TimeUnit.MINUTES);

        return TransactionResponse.fromEntity(savedTransaction);
    }

    @Override
    public TransactionResponse getTransactionById(UUID id) {
        // Fetch transaction details by unique UUID identifier
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found for id: " + id));

        return TransactionResponse.fromEntity(transaction);
    }

    @Override
    public List<TransactionResponse> getTransactionsByAccountNumber(String accountNumber) {
        // Retrieve transaction history for an account (both sent and received), newest first
        return transactionRepository
                .findBySourceAccountNumberOrTargetAccountNumberOrderByCreatedAtDesc(accountNumber, accountNumber)
                .stream()
                .map(TransactionResponse::fromEntity)
                .toList();
    }

    @Override
    public TransactionResponse verifyOtp(OtpVerificationRequest request) {
        // 1. Verify transaction exists and is currently in PENDING state
        Transaction transaction = transactionRepository.findById(UUID.fromString(request.transactionId()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalArgumentException("Transaction is already processed");
        }

        // 2. Validate the OTP against the Redis cache
        String cachedOtp = redisTemplate.opsForValue().get("otp:" + request.transactionId());

        if (cachedOtp == null || !cachedOtp.equals(request.otp())) {
            throw new IllegalArgumentException("Invalid or expired OTP");
        }

        // 3. Immediately delete OTP from Redis to prevent replay attacks (submitting same OTP twice)
        redisTemplate.delete("otp:" + request.transactionId());

        // 4. SAGA Step 1: Deduct balance from sender account via OpenFeign
        try {
            accountServiceClient.deductBalance(transaction.getSourceAccountNumber(),
                    new TransactionAmountRequest(transaction.getAmount()));
        } catch (Exception e) {
            // If deduction fails, update status to FAILED with reason so transaction is not left as PENDING
            log.error("Failed to deduct balance for transaction {}: {}", transaction.getId(), e.getMessage());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("Debit failed: " + e.getMessage());
            transactionRepository.save(transaction);
            throw e;
        }

        // 5. Update transaction state to PROCESSING (funds deducted, ready for fraud check & crediting)
        transaction.setStatus(TransactionStatus.PROCESSING);
        Transaction savedTransaction = transactionRepository.save(transaction);

        return TransactionResponse.fromEntity(savedTransaction);
    }
}
