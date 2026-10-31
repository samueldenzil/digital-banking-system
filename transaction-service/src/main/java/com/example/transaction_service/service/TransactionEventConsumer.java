package com.example.transaction_service.service;

import com.example.transaction_service.client.AccountServiceClient;
import com.example.transaction_service.client.dto.TransactionAmountRequest;
import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionStatus;
import com.example.transaction_service.event.FraudAlertEvent;
import com.example.transaction_service.event.FraudCheckClearedEvent;
import com.example.transaction_service.event.TransactionCompletedEvent;
import com.example.transaction_service.event.TransactionOtpGeneratedEvent;
import com.example.transaction_service.event.TransactionRefundedEvent;

import java.time.Instant;
import com.example.transaction_service.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Consumer for SAGA orchestration events.
 * Implements Risk-Based Authentication:
 * - If fraud is detected (high risk), generates an OTP challenge for the user.
 * - If cleared (low risk), completes the transaction and credits the recipient.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEventConsumer {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final String TRANSACTION_OTP_GENERATED_TOPIC = "transaction.otp.generated";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    private static final String FRAUD_CLEARED_TOPIC = "fraud.cleared";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";

    /**
     * Handles FraudAlertEvent when a transaction is flagged as suspicious.
     * Step-Up MFA Challenge: Generates a 6-digit OTP and caches it in Redis (5 min
     * TTL)
     * so the customer must verify their identity before funds can move.
     */
    @KafkaListener(topics = FRAUD_DETECTED_TOPIC, groupId = "transaction-service-group")
    public void handleFraudAlert(FraudAlertEvent event) {
        log.warn("Received fraud alert for transaction {}: reason={}", event.transactionId(), event.reason());

        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);

        if (transaction == null) {
            log.error("Transaction not found for ID: {}", event.transactionId());
            return;
        }

        // Idempotency check: ignore if already processed
        if (transaction.getStatus() != TransactionStatus.PENDING) {
            log.info("Transaction {} already in state {}. Skipping OTP challenge.",
                    transaction.getId(), transaction.getStatus());
            return;
        }

        // Generate 6-digit OTP and store in Redis with 5-minute TTL
        String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        redisTemplate.opsForValue().set("otp:" + transaction.getId(), otp, 5, TimeUnit.MINUTES);

        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
        transactionRepository.save(transaction);

        log.info("Step-Up Challenge: Generated OTP for flagged transaction {}: {}", transaction.getId(), otp);

        // Notify user via strongly-typed event
        TransactionOtpGeneratedEvent otpEvent = new TransactionOtpGeneratedEvent(
                transaction.getId(),
                event.accountNumber(),
                event.reason(),
                otp,
                event.amount(),
                Instant.now());

        kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC, event.transactionId().toString(), otpEvent);
    }

    /**
     * Handles FraudCheckClearedEvent when a transaction is deemed clean/low-risk.
     * Settles the transaction by crediting the destination account.
     */
    @KafkaListener(topics = FRAUD_CLEARED_TOPIC, groupId = "transaction-service-group")
    public void handleFraudCheckCleared(FraudCheckClearedEvent event) {
        log.info("Received fraud cleared event for ID: {}. Proceeding to settle.", event.transactionId());

        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);
        if (transaction == null) {
            log.error("Transaction not found for ID: {}", event.transactionId());
            return;
        }

        // Idempotency check: avoid double credit
        if (transaction.getStatus() == TransactionStatus.SUCCESS) {
            log.info("Transaction {} already settled. Skipping duplicate completion.", transaction.getId());
            return;
        }

        // Step 1: Deduct sender balance
        try {
            accountServiceClient.deductBalance(
                    transaction.getSourceAccountNumber(),
                    new TransactionAmountRequest(transaction.getAmount()));
        } catch (Exception e) {
            log.error("Debit failed during clean settlement for transaction {}: {}", transaction.getId(),
                    e.getMessage());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("Debit failed: " + e.getMessage());
            transactionRepository.save(transaction);
            return;
        }

        // Step 2: Credit recipient balance with automated refund compensation if credit
        // fails
        try {
            accountServiceClient.creditBalance(
                    transaction.getTargetAccountNumber(),
                    new TransactionAmountRequest(transaction.getAmount()));

            transaction.setStatus(TransactionStatus.SUCCESS);
            transaction.setCompletedAt(LocalDateTime.now());
            Transaction saved = transactionRepository.save(transaction);

            // Publish real TransactionCompletedEvent now that money has actually moved!
            kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, saved.getId().toString(),
                    new TransactionCompletedEvent(
                            saved.getId(),
                            saved.getSourceAccountNumber(),
                            saved.getTargetAccountNumber(),
                            saved.getAmount(),
                            Instant.now()));

            log.info("Transaction {} successfully settled without OTP challenge and completed event published.",
                    transaction.getId());
        } catch (Exception e) {
            log.error("Credit failed for transaction {}. Executing refund compensation: {}", transaction.getId(),
                    e.getMessage());
            try {
                accountServiceClient.refundBalance(
                        transaction.getSourceAccountNumber(),
                        new TransactionAmountRequest(transaction.getAmount()));
                transaction.setStatus(TransactionStatus.REFUNDED);
                transaction.setFailureReason("Target credit failed. Sender refunded: " + e.getMessage());

                kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId().toString(),
                        new TransactionRefundedEvent(transaction.getId(), transaction.getSourceAccountNumber(),
                                transaction.getAmount(), transaction.getFailureReason(), Instant.now()));
            } catch (Exception refundEx) {
                log.error("CRITICAL: Failed to compensate sender for transaction {}: {}", transaction.getId(),
                        refundEx.getMessage());
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason("Settlement and compensation failed: " + refundEx.getMessage());
            }
            transactionRepository.save(transaction);
        }
    }
}
