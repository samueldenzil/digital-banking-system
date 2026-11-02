package com.example.transaction_service.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.transaction_service.client.AccountServiceClient;
import com.example.transaction_service.client.dto.AccountResponse;
import com.example.transaction_service.client.dto.TransactionAmountRequest;
import com.example.transaction_service.dto.OtpVerificationRequest;
import com.example.transaction_service.dto.TransactionRequest;
import com.example.transaction_service.dto.TransactionResponse;
import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionStatus;
import com.example.transaction_service.entity.TransactionType;
import com.example.transaction_service.event.TransactionCompletedEvent;
import com.example.transaction_service.event.TransactionInitiatedEvent;
import com.example.transaction_service.event.TransactionRefundedEvent;
import com.example.transaction_service.repository.TransactionRepository;
import com.example.transaction_service.service.TransactionService;

import feign.FeignException;
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
@Transactional(readOnly = true)
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";

    @Override
    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        // 1. Idempotency Check: Prevent duplicate transactions caused by client network
        // retries
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

        // 3. Pre-flight Validation: Query Account Service via OpenFeign to verify
        // accounts
        AccountResponse sourceAccount = fetchAccount(request.sourceAccountNumber(), "Source");
        AccountResponse targetAccount = fetchAccount(request.targetAccountNumber(), "Target");

        // 4. Currency Check: Ensure source and target accounts match the transfer
        // currency
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
        transaction.setType(request.type() != null ? request.type() : TransactionType.TRANSFER);
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
                Instant.now());

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId().toString(), event);

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
        // Retrieve transaction history for an account (both sent and received), newest
        // first
        return transactionRepository
                .findBySourceAccountNumberOrTargetAccountNumberOrderByCreatedAtDesc(accountNumber, accountNumber)
                .stream()
                .map(TransactionResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional
    public TransactionResponse verifyOtp(OtpVerificationRequest request) {
        // 1. Verify transaction exists and is currently in PENDING_VERIFICATION state
        Transaction transaction = transactionRepository.findById(UUID.fromString(request.transactionId()))
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new IllegalArgumentException("Transaction is already processed");
        }

        // 2. Validate the OTP against the Redis cache
        String cachedOtp = redisTemplate.opsForValue().get("otp:" + request.transactionId());

        if (cachedOtp == null || !cachedOtp.equals(request.otp())) {
            throw new IllegalArgumentException("Invalid or expired OTP");
        }

        // 3. Immediately delete OTP from Redis to prevent replay attacks (submitting
        // same OTP twice)
        redisTemplate.delete("otp:" + request.transactionId());

        // 4. SAGA Step 1: Transition status to PROCESSING and deduct balance from
        // sender account via OpenFeign
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction = transactionRepository.saveAndFlush(transaction);

        try {
            accountServiceClient.deductBalance(transaction.getSourceAccountNumber(),
                    new TransactionAmountRequest(transaction.getAmount()));
        } catch (Exception e) {
            log.error("Failed to deduct balance for transaction {}: {}", transaction.getId(), e.getMessage());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("Debit failed: " + e.getMessage());
            transactionRepository.save(transaction);
            throw e;
        }

        // 5. SAGA Step 2: Settle by crediting the recipient account
        try {
            accountServiceClient.creditBalance(transaction.getTargetAccountNumber(),
                    new TransactionAmountRequest(transaction.getAmount()));
        } catch (Exception e) {
            // Compensation: If crediting recipient fails, refund sender immediately
            log.error("Failed to credit target account for transaction {}. Initiating refund compensation: {}",
                    transaction.getId(), e.getMessage());
            try {
                accountServiceClient.refundBalance(transaction.getSourceAccountNumber(),
                        new TransactionAmountRequest(transaction.getAmount()));
                transaction.setStatus(TransactionStatus.REFUNDED);
                transaction.setFailureReason("Target credit failed. Sender refunded: " + e.getMessage());
                kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId().toString(),
                        new TransactionRefundedEvent(transaction.getId(), transaction.getSourceAccountNumber(),
                                transaction.getAmount(), transaction.getFailureReason(), Instant.now()));
                Transaction savedTransaction = transactionRepository.save(transaction);
                log.info("Transaction {} successfully refunded after credit failure.", savedTransaction.getId());
                return TransactionResponse.fromEntity(savedTransaction);
            } catch (Exception refundEx) {
                log.error("CRITICAL: Failed to compensate/refund sender for transaction {}: {}",
                        transaction.getId(), refundEx.getMessage());
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason("Settlement failed and compensation failed: " + refundEx.getMessage());
                transactionRepository.save(transaction);
                throw e;
            }
        }

        // 6. Complete the transaction
        transaction.setStatus(TransactionStatus.SUCCESS);
        transaction.setCompletedAt(java.time.LocalDateTime.now());
        Transaction savedTransaction = transactionRepository.save(transaction);

        // Publish real TransactionCompletedEvent now that money has actually moved!
        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, savedTransaction.getId().toString(),
                new TransactionCompletedEvent(
                        savedTransaction.getId(),
                        savedTransaction.getSourceAccountNumber(),
                        savedTransaction.getTargetAccountNumber(),
                        savedTransaction.getAmount(),
                        Instant.now()));

        log.info("Transaction {} successfully verified and settled via OTP.", savedTransaction.getId());
        return TransactionResponse.fromEntity(savedTransaction);
    }

    /**
     * Helper method to fetch account details from Account Service via OpenFeign.
     * Maps downstream FeignException.NotFound (404) to a descriptive
     * IllegalArgumentException
     * specifying whether the source or target account is missing, and handles
     * downstream
     * service unavailability gracefully.
     */
    private AccountResponse fetchAccount(String accountNumber, String role) {
        AccountResponse account;
        try {
            account = accountServiceClient.getAccount(accountNumber);
        } catch (FeignException.NotFound e) {
            throw new IllegalArgumentException(role + " account not found: " + accountNumber);
        } catch (FeignException e) {
            log.error("Failed to communicate with Account Service for {} account [{}]: status={}",
                    role, accountNumber, e.status(), e);
            throw new IllegalStateException("Account service is temporarily unavailable. Please try again later.");
        }

        if (account == null) {
            throw new IllegalArgumentException(role + " account not found: " + accountNumber);
        }

        return account;
    }
}
