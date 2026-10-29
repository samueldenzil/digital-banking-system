package com.example.account_service.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.account_service.dto.FraudAlertEvent;
import com.example.account_service.dto.TransactionCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEventConsumer {

    private final AccountService accountService;

    @KafkaListener(topics = "fraud.detected", groupId = "account-service-group")
    public void handleFraudEvent(FraudAlertEvent event) {
        log.warn("Received fraud detected event for account: {}, reason: {}",
                event.accountNumber(), event.reason());
        accountService.blockAccount(event.accountNumber());
    }

    @KafkaListener(topics = "transaction.completed", groupId = "account-service-group")
    public void handleTransactionCompletedEvent(TransactionCompletedEvent event) {
        log.info("Processing transaction completed event: txId={}, targetAccount={}, amount={}",
                event.transactionId(), event.targetAccountNumber(), event.amount());
        accountService.creditAccount(event.targetAccountNumber(), event.amount());
    }
}
