package com.example.payment_service.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.payment_service.dto.PaymentRequest;
import com.example.payment_service.dto.PaymentResponse;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.event.PaymentCompletedEvent;
import com.example.payment_service.event.PaymentFailedEvent;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.service.PaymentService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service implementation for Stripe-based payment processing.
 * Manages PaymentIntent lifecycle, idempotency, and currency sub-unit
 * conversions.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${stripe.api-key}")
    private String stripeApiKey;

    /**
     * Initializes Stripe global configuration with the test/live secret key.
     */
    @PostConstruct
    public void initStripe() {
        if (stripeApiKey == null || stripeApiKey.isBlank()) {
            log.warn("Stripe API key is not configured. Stripe operations will fail.");
            return;
        }
        Stripe.apiKey = stripeApiKey;
        log.info("Stripe SDK initialized successfully.");
    }

    @Override
    public PaymentResponse initializePayment(PaymentRequest request) {
        // 1. Idempotency Check: Return existing payment if this transaction was already
        // initialized
        Optional<Payment> existingPayment = paymentRepository.findByTransactionId(request.transactionId());

        if (existingPayment.isPresent()) {
            log.info("Payment already initialized for transaction {}. Returning existing record.",
                    request.transactionId());
            return PaymentResponse.fromEntity(existingPayment.get());
        }

        long amountInSmallestUnit = convertToSmallestCurrencyUnit(request.amount(), request.currency());

        // 2. Build Stripe PaymentIntent parameters
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInSmallestUnit)
                .setCurrency(request.currency().toLowerCase())
                .putMetadata("transactionId", request.transactionId().toString())
                .putMetadata("accountNumber", request.accountNumber())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                .build();

        try {
            // 3. Create Intent on Stripe
            PaymentIntent intent = PaymentIntent.create(params);
            log.info("Created Stripe PaymentIntent: {} for transaction: {}", intent.getId(), request.transactionId());

            // 4. Persist internal Payment entity in PENDING state
            Payment payment = new Payment();
            payment.setAccountNumber(request.accountNumber());
            payment.setTransactionId(request.transactionId());
            payment.setAmount(request.amount());
            payment.setCurrency(request.currency().toUpperCase());
            payment.setStripePaymentIntentId(intent.getId());
            payment.setStripeClientSecret(intent.getClientSecret());
            payment.setPaymentMethod(request.paymentMethod() != null ? request.paymentMethod() : "CARD");
            payment.setStatus(PaymentStatus.PENDING);

            Payment savedPayment = paymentRepository.save(payment);
            return PaymentResponse.fromEntity(savedPayment);

        } catch (StripeException e) {
            log.error("Stripe gateway error while creating PaymentIntent for transaction {}: {}",
                    request.transactionId(), e.getMessage(), e);
            throw new RuntimeException("Stripe payment gateway error: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for id: " + id));

        return PaymentResponse.fromEntity(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByTransactionId(UUID transactionId) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for transaction: " + transactionId));

        return PaymentResponse.fromEntity(payment);
    }

    @Override
    public PaymentResponse failPayment(String stripePaymentIntentId, String failureReason) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(stripePaymentIntentId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Payment not found for intent: " + stripePaymentIntentId));

        if (payment.getStatus() == PaymentStatus.FAILED) {
            log.info("Payment for intent {} is already marked as FAILED.", stripePaymentIntentId);
            return PaymentResponse.fromEntity(payment);
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(failureReason);
        Payment saved = paymentRepository.save(payment);

        log.warn("Payment failed for transaction {} (Intent: {}): reason={}",
                saved.getTransactionId(), stripePaymentIntentId, failureReason);

        PaymentFailedEvent event = new PaymentFailedEvent(
                saved.getId(),
                saved.getTransactionId(),
                saved.getAccountNumber(),
                saved.getAmount(),
                saved.getCurrency(),
                saved.getStripePaymentIntentId(),
                saved.getFailureReason(),
                Instant.now());

        kafkaTemplate.send("payment.failed", saved.getTransactionId().toString(), event);

        return PaymentResponse.fromEntity(saved);
    }

    @Override
    public PaymentResponse confirmPayment(String stripePaymentIntentId) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(stripePaymentIntentId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Payment not found for intent: " + stripePaymentIntentId));

        // Idempotency: If already completed, return immediately
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.info("Payment for intent {} is already marked as COMPLETED.", stripePaymentIntentId);
            return PaymentResponse.fromEntity(payment);
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(payment);

        log.info("Payment confirmed successfully for transaction {} (Intent: {})",
                saved.getTransactionId(), stripePaymentIntentId);

        PaymentCompletedEvent event = new PaymentCompletedEvent(
                saved.getId(),
                saved.getTransactionId(),
                saved.getAccountNumber(),
                saved.getAmount(),
                saved.getCurrency(),
                saved.getStripePaymentIntentId(),
                Instant.now());

        kafkaTemplate.send("payment.completed", saved.getTransactionId().toString(), event);

        return PaymentResponse.fromEntity(saved);
    }

    /**
     * Converts monetary amount to smallest currency sub-unit as required by Stripe:
     * - Standard 2-decimal currencies (USD, EUR, INR, GBP, AED, etc.): amount * 100
     * - Zero-decimal currencies (JPY, KRW, VND, etc.): amount * 1
     * - 3-decimal currencies (KWD, BHD, OMR, etc.): amount * 1000
     */
    private long convertToSmallestCurrencyUnit(BigDecimal amount, String currency) {
        if (currency == null) {
            return amount.multiply(BigDecimal.valueOf(100)).longValue();
        }

        String curr = currency.toUpperCase();

        // 1. Zero-decimal currencies
        if (Set.of("JPY", "KRW", "VND", "CLP", "PYG", "UGX").contains(curr)) {
            return amount.longValue();
        }

        // 2. Three-decimal currencies
        if (Set.of("BHD", "JOD", "KWD", "OMR", "TND").contains(curr)) {
            return amount.multiply(BigDecimal.valueOf(1000)).longValue();
        }

        // 3. Default: standard 2-decimal currencies
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }

}
