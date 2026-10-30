package com.example.fraud_detection_service.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.fraud_detection_service.event.TransactionInitiatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudEventConsumer {

    private final FraudDetectionService fraudDetectionService;

    @KafkaListener(topics = "transaction.initiated", groupId = "fraud-detection-group")
    public void consumeTransactionInitiated(TransactionInitiatedEvent event) {
        log.info("Received transaction for fraud check: ID={}, amount={}", event.transactionId(), event.amount());

        fraudDetectionService.checkTransaction(event);
    }
}
