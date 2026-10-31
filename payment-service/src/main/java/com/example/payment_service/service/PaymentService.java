package com.example.payment_service.service;

import java.util.UUID;

import com.example.payment_service.dto.PaymentRequest;
import com.example.payment_service.dto.PaymentResponse;

public interface PaymentService {
    PaymentResponse initializePayment(PaymentRequest request);

    PaymentResponse getPaymentById(UUID id);

    PaymentResponse getPaymentByTransactionId(UUID transactionId);

    PaymentResponse failPayment(String stripePaymentIntentId, String failureReason);

    PaymentResponse confirmPayment(String stripePaymentIntentId);
}
