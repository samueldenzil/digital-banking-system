package com.example.fraud_detection_service.service;

import com.example.fraud_detection_service.event.TransactionInitiatedEvent;

public interface FraudDetectionService {

    void checkTransaction(TransactionInitiatedEvent event);
}
