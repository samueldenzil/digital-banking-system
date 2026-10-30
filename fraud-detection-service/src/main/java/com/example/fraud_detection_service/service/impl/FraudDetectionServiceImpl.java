package com.example.fraud_detection_service.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.fraud_detection_service.client.AccountServiceClient;
import com.example.fraud_detection_service.client.dto.AccountResponse;
import com.example.fraud_detection_service.event.FraudAlertEvent;
import com.example.fraud_detection_service.event.FraudCheckClearedEvent;
import com.example.fraud_detection_service.event.TransactionInitiatedEvent;
import com.example.fraud_detection_service.model.FraudCheckResult;
import com.example.fraud_detection_service.service.FraudDetectionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionServiceImpl implements FraudDetectionService {

    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${application.fraud.max-transaction-per-minute:5}")
    private int maxTransactionsPerMinute;

    @Value("${application.fraud.suspicious-amount-multiplier:3.0}")
    private double suspiciousAmountMultiplier;

    @Value("${application.fraud.max-balance-percentage:0.90}")
    private double maxBalancePercentage;

    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    private static final String FRAUD_CLEARED_TOPIC = "fraud.cleared";

    // Redis key namespace prefixes
    private static final String VELOCITY_KEY_PREFIX = "fraud:velocity:";
    private static final String AVG_AMOUNT_KEY_PREFIX = "fraud:avg_amount:";
    private static final long VELOCITY_WINDOW_SECONDS = 60L;

    @Override
    public void checkTransaction(TransactionInitiatedEvent event) {
        UUID transactionId = event.transactionId();
        String sourceAccountNumber = event.sourceAccountNumber();
        BigDecimal amount = event.amount();

        log.info("Evaluating transaction {} for fraud: account={}, amount={}",
                transactionId, sourceAccountNumber, amount);

        AccountResponse sourceAccount = accountServiceClient.getAccount(sourceAccountNumber);

        FraudCheckResult result = performFraudCheck(sourceAccount, amount);

        if (result.isFraud()) {
            log.warn("Fraud detected for transaction {}: reason={}", transactionId, result.reason());

            FraudAlertEvent fraudAlert = new FraudAlertEvent(
                    transactionId,
                    sourceAccountNumber,
                    amount,
                    result.reason(),
                    Instant.now());

            kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transactionId.toString(), fraudAlert);
        } else {
            log.info("Transaction {} cleared fraud evaluation. Publishing fraud.cleared event.", transactionId);

            FraudCheckClearedEvent clearedEvent = new FraudCheckClearedEvent(
                    transactionId,
                    sourceAccountNumber,
                    event.targetAccountNumber(),
                    amount,
                    Instant.now());

            kafkaTemplate.send(FRAUD_CLEARED_TOPIC, transactionId.toString(), clearedEvent);
        }
    }

    /**
     * Runs three sequential fraud detection heuristics:
     * 1. Velocity check: limits rapid-fire requests within a 60-second window.
     * 2. Spike check: flags amounts deviating significantly from historical
     * average.
     * 3. Drain check: flags single transfers attempting to drain > 90% of current
     * balance.
     */
    private FraudCheckResult performFraudCheck(AccountResponse account, BigDecimal amount) {
        // Pattern 1: Velocity Check (rate of transactions)
        if (isVelocityExceeded(account.accountNumber())) {
            return FraudCheckResult.fraud("Velocity limit exceeded: too many transactions within 60 seconds");
        }

        // Pattern 2: Historical Average Spike Check
        if (isAmountSuspicious(account.accountNumber(), amount)) {
            return FraudCheckResult.fraud(
                    "Unusual transaction amount: exceeds " + suspiciousAmountMultiplier + "x historical average");
        }

        // Pattern 3: Balance Drain Check (e.g. > 90% of available balance)
        if (isBalanceDrainRisk(account, amount)) {
            return FraudCheckResult.fraud("High risk transfer: amount exceeds " + (int) (maxBalancePercentage * 100)
                    + "% of account balance");
        }

        return FraudCheckResult.clean();
    }

    /**
     * Uses Redis INCR with a sliding 60-second TTL to detect transaction velocity
     * spikes.
     */
    private boolean isVelocityExceeded(String accountNumber) {
        String key = VELOCITY_KEY_PREFIX + accountNumber;
        Long count = redisTemplate.opsForValue().increment(key);

        // Set expiration on first increment to establish 60-second sliding window
        if (count != null && count == 1) {
            redisTemplate.expire(key, VELOCITY_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        return count != null && count > maxTransactionsPerMinute;
    }

    /**
     * Compares incoming amount against customer's running average stored in Redis.
     * Updates the running average only if the transaction passes the threshold.
     */
    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount) {
        String key = AVG_AMOUNT_KEY_PREFIX + accountNumber;
        String avgStr = redisTemplate.opsForValue().get(key);

        // First transaction baseline: initialize running average and pass
        if (avgStr == null) {
            redisTemplate.opsForValue().set(key, amount.toString());
            return false;
        }

        BigDecimal avgAmount = new BigDecimal(avgStr);
        BigDecimal threshold = avgAmount.multiply(BigDecimal.valueOf(suspiciousAmountMultiplier));

        if (amount.compareTo(threshold) > 0) {
            return true; // Suspicious: do not contaminate running average
        }

        // Update running average using simple moving average: (avg + current) / 2
        BigDecimal updatedAvg = avgAmount.add(amount)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        redisTemplate.opsForValue().set(key, updatedAvg.toString());

        return false;
    }

    /**
     * Prevents account drainage by flagging transfers consuming more than the
     * configured percentage of balance.
     */
    private boolean isBalanceDrainRisk(AccountResponse account, BigDecimal amount) {
        if (account.balance() == null || account.balance().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal maxAllowedAmount = account.balance().multiply(BigDecimal.valueOf(maxBalancePercentage));
        return amount.compareTo(maxAllowedAmount) > 0;
    }
}