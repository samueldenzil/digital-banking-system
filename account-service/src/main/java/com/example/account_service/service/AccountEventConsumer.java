package com.example.account_service.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.account_service.dto.FraudAlertEvent;
import com.example.account_service.exception.AccountNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEventConsumer {

    private final AccountService accountService;

    @KafkaListener(topics = "fraud.detected", groupId = "account-service-group")
    public void handleFraudEvent(FraudAlertEvent event) {
        if (event == null || event.accountNumber() == null || event.accountNumber().isBlank()) {
            log.error("Received null or malformed fraud alert event: {}", event);
            return;
        }

        log.warn("Processing fraud detected event: txId={}, account={}, reason={}",
                event.transactionId(), event.accountNumber(), event.reason());

        try {
            accountService.blockAccount(event.accountNumber());
            log.info("Successfully froze account {} following fraud alert.", event.accountNumber());
        } catch (AccountNotFoundException ex) {
            // Fatal semantic error: Account does not exist in account-service.
            // Caught and logged with full audit context to prevent infinite consumer partition retry loops.
            log.error("Fraud alert received for non-existent account: {}. Reason: {}. Error: {}",
                    event.accountNumber(), event.reason(), ex.getMessage());
        }
    }

}
