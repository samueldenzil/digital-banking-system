package com.example.transaction_service.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import com.example.transaction_service.client.AccountServiceClient;
import com.example.transaction_service.client.dto.AccountResponse;
import com.example.transaction_service.client.dto.TransactionAmountRequest;
import com.example.transaction_service.entity.Transaction;
import com.example.transaction_service.entity.TransactionStatus;
import com.example.transaction_service.entity.TransactionType;
import com.example.transaction_service.event.FraudAlertEvent;
import com.example.transaction_service.event.FraudCheckClearedEvent;
import com.example.transaction_service.event.TransactionCompletedEvent;
import com.example.transaction_service.event.TransactionOtpGeneratedEvent;
import com.example.transaction_service.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionEventConsumerTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransactionEventConsumer(
                transactionRepository,
                accountServiceClient,
                kafkaTemplate,
                redisTemplate);
    }

    @Test
    @DisplayName("SECURE_RANDOM should be declared as private static final to avoid re-instantiation overhead")
    void shouldHaveStaticFinalSecureRandomField() throws NoSuchFieldException {
        Field secureRandomField = TransactionEventConsumer.class.getDeclaredField("SECURE_RANDOM");
        assertThat(secureRandomField.getType()).isEqualTo(SecureRandom.class);
        int modifiers = secureRandomField.getModifiers();
        assertThat(Modifier.isPrivate(modifiers)).as("SECURE_RANDOM must be private").isTrue();
        assertThat(Modifier.isStatic(modifiers)).as("SECURE_RANDOM must be static").isTrue();
        assertThat(Modifier.isFinal(modifiers)).as("SECURE_RANDOM must be final").isTrue();
    }

    @Test
    @DisplayName("handleFraudAlert should generate 6-digit OTP, cache in Redis with 5 min TTL, and emit OTP event")
    void shouldHandleFraudAlertSuccessfully() {
        UUID txnId = UUID.randomUUID();
        FraudAlertEvent alertEvent = new FraudAlertEvent(
                txnId,
                "100000000001",
                new BigDecimal("750.00"),
                "Unusual location detected",
                Instant.now());

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setSourceAccountNumber("100000000001");
        transaction.setTargetAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("750.00"));
        transaction.setType(TransactionType.TRANSFER);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));

        consumer.handleFraudAlert(alertEvent);

        // Verify status transitioned to PENDING_VERIFICATION
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.PENDING_VERIFICATION);
        verify(transactionRepository).save(transaction);

        // Verify OTP cached in Redis with 5-minute TTL
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("otp:" + txnId), otpCaptor.capture(), eq(5L), eq(TimeUnit.MINUTES));
        String generatedOtp = otpCaptor.getValue();
        assertThat(generatedOtp).matches("^\\d{6}$");

        // Verify strongly-typed event published to Kafka
        ArgumentCaptor<TransactionOtpGeneratedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionOtpGeneratedEvent.class);
        verify(kafkaTemplate).send(eq("transaction.otp.generated"), eq(txnId.toString()), eventCaptor.capture());
        TransactionOtpGeneratedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.transactionId()).isEqualTo(txnId);
        assertThat(publishedEvent.otp()).isEqualTo(generatedOtp);
        assertThat(publishedEvent.reason()).isEqualTo("Unusual location detected");
    }

    @Test
    @DisplayName("handleFraudAlert should skip processing when transaction is not in PENDING state")
    void shouldSkipFraudAlertWhenTransactionNotInPendingState() {
        UUID txnId = UUID.randomUUID();
        FraudAlertEvent alertEvent = new FraudAlertEvent(
                txnId,
                "100000000001",
                new BigDecimal("500.00"),
                "High risk flag",
                Instant.now());

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setStatus(TransactionStatus.SUCCESS);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));

        consumer.handleFraudAlert(alertEvent);

        verify(redisTemplate, never()).opsForValue();
        verify(transactionRepository, never()).save(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("handleFraudCheckCleared should settle transaction and emit TransactionCompletedEvent")
    void shouldHandleFraudCheckClearedSuccessfully() {
        UUID txnId = UUID.randomUUID();
        FraudCheckClearedEvent clearedEvent = new FraudCheckClearedEvent(
                txnId,
                "100000000001",
                "100000000002",
                new BigDecimal("250.00"),
                Instant.now());

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setSourceAccountNumber("100000000001");
        transaction.setTargetAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("250.00"));
        transaction.setCurrency("USD");
        transaction.setType(TransactionType.TRANSFER);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse debitResponse = new AccountResponse(UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("750.00"), "USD", "ACTIVE");
        when(accountServiceClient.deductBalance(eq("100000000001"), any(TransactionAmountRequest.class))).thenReturn(debitResponse);

        AccountResponse creditResponse = new AccountResponse(UUID.randomUUID(), "100000000002", "CUST_2", new BigDecimal("500.00"), "USD", "ACTIVE");
        when(accountServiceClient.creditBalance(eq("100000000002"), any(TransactionAmountRequest.class))).thenReturn(creditResponse);

        consumer.handleFraudCheckCleared(clearedEvent);

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(transaction.getCompletedAt()).isNotNull();

        verify(accountServiceClient).deductBalance(eq("100000000001"), any(TransactionAmountRequest.class));
        verify(accountServiceClient).creditBalance(eq("100000000002"), any(TransactionAmountRequest.class));
        verify(kafkaTemplate).send(eq("transaction.completed"), eq(txnId.toString()), any(TransactionCompletedEvent.class));
    }
}
