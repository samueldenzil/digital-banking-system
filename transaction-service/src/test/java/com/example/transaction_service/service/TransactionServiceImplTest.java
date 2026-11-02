package com.example.transaction_service.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
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
import com.example.transaction_service.service.impl.TransactionServiceImpl;

import feign.FeignException;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TransactionServiceImpl transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionServiceImpl(
                transactionRepository,
                accountServiceClient,
                redisTemplate,
                kafkaTemplate);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when source account is not found in Account Service (Feign 404)")
    void shouldThrowIllegalArgumentExceptionWhenSourceAccountNotFound() {
        TransactionRequest request = new TransactionRequest(
                "100000000001",
                "100000000002",
                new BigDecimal("500.00"),
                "USD",
                "Payment",
                null);

        FeignException.NotFound feignNotFound = mock(FeignException.NotFound.class);
        when(accountServiceClient.getAccount("100000000001")).thenThrow(feignNotFound);

        assertThatThrownBy(() -> transactionService.createTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source account not found: 100000000001");

        verify(accountServiceClient).getAccount("100000000001");
        verify(accountServiceClient, never()).getAccount("100000000002");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when target account is not found in Account Service (Feign 404)")
    void shouldThrowIllegalArgumentExceptionWhenTargetAccountNotFound() {
        TransactionRequest request = new TransactionRequest(
                "100000000001",
                "100000000002",
                new BigDecimal("500.00"),
                "USD",
                "Payment",
                null);

        AccountResponse sourceAccount = new AccountResponse(
                UUID.randomUUID(),
                "100000000001",
                "CUST_1",
                new BigDecimal("1000.00"),
                "USD",
                "ACTIVE");

        when(accountServiceClient.getAccount("100000000001")).thenReturn(sourceAccount);
        FeignException.NotFound feignNotFound = mock(FeignException.NotFound.class);
        when(accountServiceClient.getAccount("100000000002")).thenThrow(feignNotFound);

        assertThatThrownBy(() -> transactionService.createTransaction(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Target account not found: 100000000002");

        verify(accountServiceClient).getAccount("100000000001");
        verify(accountServiceClient).getAccount("100000000002");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw IllegalStateException when Account Service is unavailable (generic FeignException)")
    void shouldThrowIllegalStateExceptionWhenAccountServiceUnavailable() {
        TransactionRequest request = new TransactionRequest(
                "100000000001",
                "100000000002",
                new BigDecimal("500.00"),
                "USD",
                "Payment",
                null);

        FeignException genericFeignException = mock(FeignException.class);
        when(genericFeignException.status()).thenReturn(503);
        when(accountServiceClient.getAccount("100000000001")).thenThrow(genericFeignException);

        assertThatThrownBy(() -> transactionService.createTransaction(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Account service is temporarily unavailable. Please try again later.");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully create transaction when pre-flight checks pass")
    void shouldCreateTransactionWhenPreflightChecksPass() {
        TransactionRequest request = new TransactionRequest(
                "100000000001",
                "100000000002",
                new BigDecimal("200.00"),
                "USD",
                "Payment",
                "idemp-123");

        AccountResponse sourceAccount = new AccountResponse(
                UUID.randomUUID(),
                "100000000001",
                "CUST_1",
                new BigDecimal("1000.00"),
                "USD",
                "ACTIVE");

        AccountResponse targetAccount = new AccountResponse(
                UUID.randomUUID(),
                "100000000002",
                "CUST_2",
                new BigDecimal("500.00"),
                "USD",
                "ACTIVE");

        when(transactionRepository.findByIdempotencyKey("idemp-123")).thenReturn(Optional.empty());
        when(accountServiceClient.getAccount("100000000001")).thenReturn(sourceAccount);
        when(accountServiceClient.getAccount("100000000002")).thenReturn(targetAccount);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction txn = invocation.getArgument(0);
            txn.setId(UUID.randomUUID());
            return txn;
        });

        TransactionResponse response = transactionService.createTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.sourceAccountNumber()).isEqualTo("100000000001");
        assertThat(response.targetAccountNumber()).isEqualTo("100000000002");
        assertThat(response.amount()).isEqualByComparingTo("200.00");
        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);

        verify(kafkaTemplate).send(eq("transaction.initiated"), anyString(), any());
    }

    @Test
    @DisplayName("Should create transaction with explicit TransactionType when specified in request")
    void shouldCreateTransactionWithExplicitTransactionType() {
        TransactionRequest request = new TransactionRequest(
                "100000000001",
                "100000000002",
                new BigDecimal("350.00"),
                "USD",
                "Bill Payment",
                "idemp-pay-1",
                TransactionType.PAYMENT);

        AccountResponse sourceAccount = new AccountResponse(
                UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("1000.00"), "USD", "ACTIVE");
        AccountResponse targetAccount = new AccountResponse(
                UUID.randomUUID(), "100000000002", "CUST_2", new BigDecimal("500.00"), "USD", "ACTIVE");

        when(transactionRepository.findByIdempotencyKey("idemp-pay-1")).thenReturn(Optional.empty());
        when(accountServiceClient.getAccount("100000000001")).thenReturn(sourceAccount);
        when(accountServiceClient.getAccount("100000000002")).thenReturn(targetAccount);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction txn = invocation.getArgument(0);
            txn.setId(UUID.randomUUID());
            return txn;
        });

        TransactionResponse response = transactionService.createTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.type()).isEqualTo(TransactionType.PAYMENT);

        ArgumentCaptor<TransactionInitiatedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionInitiatedEvent.class);
        verify(kafkaTemplate).send(eq("transaction.initiated"), anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(TransactionType.PAYMENT);
    }

    @Test
    @DisplayName("Should default to TransactionType.TRANSFER when type is null in TransactionRequest")
    void shouldDefaultToTransferWhenTransactionTypeIsNull() {
        TransactionRequest request = new TransactionRequest(
                "100000000001",
                "100000000002",
                new BigDecimal("100.00"),
                "USD",
                "Default transfer test",
                "idemp-null-type",
                null);

        AccountResponse sourceAccount = new AccountResponse(
                UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("1000.00"), "USD", "ACTIVE");
        AccountResponse targetAccount = new AccountResponse(
                UUID.randomUUID(), "100000000002", "CUST_2", new BigDecimal("500.00"), "USD", "ACTIVE");

        when(transactionRepository.findByIdempotencyKey("idemp-null-type")).thenReturn(Optional.empty());
        when(accountServiceClient.getAccount("100000000001")).thenReturn(sourceAccount);
        when(accountServiceClient.getAccount("100000000002")).thenReturn(targetAccount);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction txn = invocation.getArgument(0);
            txn.setId(UUID.randomUUID());
            return txn;
        });

        TransactionResponse response = transactionService.createTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
    }

    @Test
    @DisplayName("Should refund sender and return REFUNDED response without re-throwing FeignException when credit fails")
    void shouldRefundSenderAndReturnRefundedResponseWhenCreditFailsWithFeignException() {
        UUID txnId = UUID.randomUUID();
        OtpVerificationRequest otpRequest = new OtpVerificationRequest(txnId.toString(), "123456");

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setSourceAccountNumber("100000000001");
        transaction.setTargetAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("150.00"));
        transaction.setCurrency("USD");
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:" + txnId)).thenReturn("123456");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Step 1: debit succeeds
        AccountResponse debitResponse = new AccountResponse(UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("850.00"), "USD", "ACTIVE");
        when(accountServiceClient.deductBalance(eq("100000000001"), any(TransactionAmountRequest.class))).thenReturn(debitResponse);

        // Step 2: credit recipient fails with FeignException
        FeignException creditFeignException = mock(FeignException.class);
        when(creditFeignException.getMessage()).thenReturn("Target account inactive");
        when(accountServiceClient.creditBalance(eq("100000000002"), any(TransactionAmountRequest.class))).thenThrow(creditFeignException);

        // Step 3 (Compensation): refund to sender succeeds
        AccountResponse refundResponse = new AccountResponse(UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("1000.00"), "USD", "ACTIVE");
        when(accountServiceClient.refundBalance(eq("100000000001"), any(TransactionAmountRequest.class))).thenReturn(refundResponse);

        // Act - verifyOtp must handle compensation gracefully and NOT re-throw FeignException
        TransactionResponse response = transactionService.verifyOtp(otpRequest);

        // Assert: Response indicates REFUNDED status with clear explanation
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(response.failureReason()).contains("Target credit failed. Sender refunded");
        assertThat(response.sourceAccountNumber()).isEqualTo("100000000001");
        assertThat(response.targetAccountNumber()).isEqualTo("100000000002");
        assertThat(response.amount()).isEqualByComparingTo("150.00");

        // Verify Redis OTP was deleted to avoid replays
        verify(redisTemplate).delete("otp:" + txnId);

        // Verify Feign client interactions
        verify(accountServiceClient).deductBalance(eq("100000000001"), any(TransactionAmountRequest.class));
        verify(accountServiceClient).creditBalance(eq("100000000002"), any(TransactionAmountRequest.class));
        verify(accountServiceClient).refundBalance(eq("100000000001"), any(TransactionAmountRequest.class));

        // Verify Kafka event publishing: refunded event sent, completed event never sent
        verify(kafkaTemplate).send(eq("transaction.refunded"), eq(txnId.toString()), any(TransactionRefundedEvent.class));
        verify(kafkaTemplate, never()).send(eq("transaction.completed"), anyString(), any());
    }

    @Test
    @DisplayName("Should throw FeignException when credit fails and refund compensation also fails")
    void shouldThrowExceptionWhenCreditFailsAndRefundCompensationFails() {
        UUID txnId = UUID.randomUUID();
        OtpVerificationRequest otpRequest = new OtpVerificationRequest(txnId.toString(), "123456");

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setSourceAccountNumber("100000000001");
        transaction.setTargetAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("150.00"));
        transaction.setCurrency("USD");
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:" + txnId)).thenReturn("123456");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Step 1: debit succeeds
        AccountResponse debitResponse = new AccountResponse(UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("850.00"), "USD", "ACTIVE");
        when(accountServiceClient.deductBalance(eq("100000000001"), any(TransactionAmountRequest.class)))
                .thenReturn(debitResponse);

        // Step 2: credit recipient fails
        FeignException creditFeignException = mock(FeignException.class);
        when(creditFeignException.getMessage()).thenReturn("Target account inactive");
        when(accountServiceClient.creditBalance(eq("100000000002"), any(TransactionAmountRequest.class)))
                .thenThrow(creditFeignException);

        // Compensation: refund also fails
        FeignException refundException = mock(FeignException.class);
        when(refundException.getMessage()).thenReturn("Account service unavailable for refund");
        when(accountServiceClient.refundBalance(eq("100000000001"), any(TransactionAmountRequest.class)))
                .thenThrow(refundException);

        // Act & Assert
        assertThatThrownBy(() -> transactionService.verifyOtp(otpRequest))
                .isSameAs(creditFeignException);

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(transaction.getFailureReason()).contains("Settlement failed and compensation failed");

        verify(kafkaTemplate, never()).send(eq("transaction.refunded"), anyString(), any());
        verify(kafkaTemplate, never()).send(eq("transaction.completed"), anyString(), any());
    }

    @Test
    @DisplayName("Should successfully verify OTP, settle funds, and return SUCCESS response")
    void shouldVerifyOtpAndSettleSuccessfully() {
        UUID txnId = UUID.randomUUID();
        OtpVerificationRequest otpRequest = new OtpVerificationRequest(txnId.toString(), "123456");

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setSourceAccountNumber("100000000001");
        transaction.setTargetAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("150.00"));
        transaction.setCurrency("USD");
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:" + txnId)).thenReturn("123456");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse debitResponse = new AccountResponse(UUID.randomUUID(), "100000000001", "CUST_1", new BigDecimal("850.00"), "USD", "ACTIVE");
        when(accountServiceClient.deductBalance(eq("100000000001"), any(TransactionAmountRequest.class))).thenReturn(debitResponse);

        AccountResponse creditResponse = new AccountResponse(UUID.randomUUID(), "100000000002", "CUST_2", new BigDecimal("650.00"), "USD", "ACTIVE");
        when(accountServiceClient.creditBalance(eq("100000000002"), any(TransactionAmountRequest.class))).thenReturn(creditResponse);

        TransactionResponse response = transactionService.verifyOtp(otpRequest);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.completedAt()).isNotNull();

        verify(redisTemplate).delete("otp:" + txnId);
        verify(accountServiceClient).deductBalance(eq("100000000001"), any(TransactionAmountRequest.class));
        verify(accountServiceClient).creditBalance(eq("100000000002"), any(TransactionAmountRequest.class));
        verify(accountServiceClient, never()).refundBalance(anyString(), any());
        verify(kafkaTemplate).send(eq("transaction.completed"), eq(txnId.toString()), any(TransactionCompletedEvent.class));
        verify(kafkaTemplate, never()).send(eq("transaction.refunded"), anyString(), any());
    }

    @Test
    @DisplayName("Should mark transaction FAILED and rethrow when debit fails in verifyOtp")
    void shouldFailTransactionWhenDebitFailsInVerifyOtp() {
        UUID txnId = UUID.randomUUID();
        OtpVerificationRequest otpRequest = new OtpVerificationRequest(txnId.toString(), "123456");

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setSourceAccountNumber("100000000001");
        transaction.setTargetAccountNumber("100000000002");
        transaction.setAmount(new BigDecimal("150.00"));
        transaction.setCurrency("USD");
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:" + txnId)).thenReturn("123456");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));
        when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FeignException debitException = mock(FeignException.class);
        when(debitException.getMessage()).thenReturn("Insufficient balance at debit");
        when(accountServiceClient.deductBalance(eq("100000000001"), any(TransactionAmountRequest.class)))
                .thenThrow(debitException);

        assertThatThrownBy(() -> transactionService.verifyOtp(otpRequest))
                .isSameAs(debitException);

        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(transaction.getFailureReason()).contains("Debit failed");

        verify(accountServiceClient, never()).creditBalance(anyString(), any());
        verify(accountServiceClient, never()).refundBalance(anyString(), any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when OTP does not match Redis")
    void shouldThrowIllegalArgumentExceptionWhenOtpDoesNotMatch() {
        UUID txnId = UUID.randomUUID();
        OtpVerificationRequest otpRequest = new OtpVerificationRequest(txnId.toString(), "999999");

        Transaction transaction = new Transaction();
        transaction.setId(txnId);
        transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("otp:" + txnId)).thenReturn("123456");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.verifyOtp(otpRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid or expired OTP");

        verify(redisTemplate, never()).delete(anyString());
        verify(accountServiceClient, never()).deductBalance(anyString(), any());
    }

    @Test
    @DisplayName("TransactionServiceImpl class and mutating methods should have proper @Transactional boundaries")
    void shouldHaveTransactionalBoundariesConfigured() throws NoSuchMethodException {
        // Class-level: default to read-only transaction boundary
        Transactional classTransactional = TransactionServiceImpl.class.getAnnotation(Transactional.class);
        assertThat(classTransactional)
                .as("TransactionServiceImpl must be annotated with @Transactional(readOnly = true)")
                .isNotNull();
        assertThat(classTransactional.readOnly()).isTrue();

        // Mutating method: createTransaction must be @Transactional(readOnly = false)
        Transactional createTxnTransactional = TransactionServiceImpl.class
                .getMethod("createTransaction", TransactionRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(createTxnTransactional)
                .as("createTransaction must be annotated with mutating @Transactional")
                .isNotNull();
        assertThat(createTxnTransactional.readOnly()).isFalse();

        // Mutating method: verifyOtp must be @Transactional(readOnly = false)
        Transactional verifyOtpTransactional = TransactionServiceImpl.class
                .getMethod("verifyOtp", OtpVerificationRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(verifyOtpTransactional)
                .as("verifyOtp must be annotated with mutating @Transactional")
                .isNotNull();
        assertThat(verifyOtpTransactional.readOnly()).isFalse();

        // Read-only queries should not have readOnly = false overriding the class default
        Transactional getByIdTransactional = TransactionServiceImpl.class
                .getMethod("getTransactionById", UUID.class)
                .getAnnotation(Transactional.class);
        if (getByIdTransactional != null) {
            assertThat(getByIdTransactional.readOnly()).isTrue();
        }

        Transactional getByAccountTransactional = TransactionServiceImpl.class
                .getMethod("getTransactionsByAccountNumber", String.class)
                .getAnnotation(Transactional.class);
        if (getByAccountTransactional != null) {
            assertThat(getByAccountTransactional.readOnly()).isTrue();
        }
    }
}
