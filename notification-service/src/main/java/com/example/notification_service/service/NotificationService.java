package com.example.notification_service.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.notification_service.event.FraudAlertEvent;
import com.example.notification_service.event.PaymentCompletedEvent;
import com.example.notification_service.event.PaymentFailedEvent;
import com.example.notification_service.event.TransactionCompletedEvent;
import com.example.notification_service.event.TransactionOtpGeneratedEvent;
import com.example.notification_service.event.TransactionRefundedEvent;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class NotificationService {

        @KafkaListener(topics = "transaction.otp.generated", groupId = "notification-service-group")
        public void handleTransactionOtpGenerated(TransactionOtpGeneratedEvent event) {
                String accountNumber = event.accountNumber();
                String otp = event.otp();
                UUID transactionId = event.transactionId();
                BigDecimal amount = event.amount();
                String reason = event.reason();

                String subject = "SECURITY ALERT: Verification Required for Transfer";
                String message = String.format(
                                "Security Alert for Account [%s]: A transfer of %s (Tx ID: %s) triggered a safety check (%s). "
                                                + "Your one-time verification code is: %s. This OTP is valid for 5 minutes. "
                                                + "Never share this code with anyone. If you did not initiate this transfer, please contact support immediately.",
                                accountNumber, amount, transactionId, reason, otp);

                sendAlert(accountNumber, subject, message);
        }

        @KafkaListener(topics = "fraud.detected", groupId = "notification-service-group")
        public void handleFraudDetected(FraudAlertEvent event) {
                String accountNumber = event.accountNumber();
                BigDecimal amount = event.amount();
                UUID transactionId = event.transactionId();
                String fraudReason = event.reason();

                String subject = "URGENT SECURITY ALERT: Suspicious Activity Detected on Account";

                String message = String.format(
                                "Critical Security Alert for Account [%s]: A transfer of %s (Tx ID: %s) was flagged and blocked by our automated fraud detection system. "
                                                + "Detection flag: %s. "
                                                + "A temporary security hold may have been placed on your account to safeguard your funds. "
                                                + "If you did not initiate this transaction, please contact our 24/7 fraud prevention department immediately.",
                                accountNumber, amount, transactionId, fraudReason);

                sendAlert(accountNumber, subject, message);
        }

        @KafkaListener(topics = "transaction.refunded", groupId = "notification-service-group")
        public void handleTransactionRefund(TransactionRefundedEvent event) {
                String accountNumber = event.senderAccountNumber();
                BigDecimal amount = event.amount();
                UUID transactionId = event.transactionId();
                String reason = event.reason();

                String subject = "TRANSACTION UPDATE: Transfer Failed & Funds Refunded";
                String message = String.format(
                                "Notice for Account [%s]: Your transfer of %s (Tx ID: %s) could not be completed. "
                                                + "Reason: %s. "
                                                + "The full amount has been refunded back to your account balance. "
                                                + "No action is required.",
                                accountNumber, amount, transactionId, reason);

                sendAlert(accountNumber, subject, message);
        }

        @KafkaListener(topics = "transaction.completed", groupId = "notification-service-group")
        public void handleTransactionCompleted(TransactionCompletedEvent event) {
                UUID transactionId = event.transactionId();
                String sourceAccount = event.sourceAccountNumber();
                String targetAccount = event.targetAccountNumber();
                BigDecimal amount = event.amount();

                // 1. Notify Sender (Debit Confirmation)
                String senderSubject = "TRANSFER CONFIRMATION: Funds Successfully Sent";
                String senderMessage = String.format(
                                "Debit Alert for Account [%s]: Successfully sent %s to Account [%s]. Transaction ID: %s.",
                                sourceAccount, amount, targetAccount, transactionId);
                sendAlert(sourceAccount, senderSubject, senderMessage);

                // 2. Notify Recipient (Credit Confirmation)
                String recipientSubject = "CREDIT ALERT: Funds Received";
                String recipientMessage = String.format(
                                "Credit Alert for Account [%s]: You received %s from Account [%s]. Transaction ID: %s.",
                                targetAccount, amount, sourceAccount, transactionId);
                sendAlert(targetAccount, recipientSubject, recipientMessage);
        }

        @KafkaListener(topics = "payment.completed", groupId = "notification-service-group")
        public void handlePaymentCompleted(PaymentCompletedEvent event) {
                String accountNumber = event.accountNumber();
                BigDecimal amount = event.amount();
                String currency = event.currency();
                UUID transactionId = event.transactionId();
                String gatewayPaymentId = event.gatewayPaymentId();

                String subject = "PAYMENT CONFIRMATION: Funds Successfully Deposited";
                String message = String.format(
                                "Deposit Alert for Account [%s]: Your payment of %s %s has been successfully processed. "
                                                + "Transaction ID: %s | Gateway Reference: %s. "
                                                + "Your funds are now available in your balance.",
                                accountNumber, amount, currency, transactionId, gatewayPaymentId);

                sendAlert(accountNumber, subject, message);
        }

        @KafkaListener(topics = "payment.failed", groupId = "notification-service-group")
        public void handlePaymentFailed(PaymentFailedEvent event) {
                String accountNumber = event.accountNumber();
                BigDecimal amount = event.amount();
                String currency = event.currency();
                UUID transactionId = event.transactionId();
                String gatewayPaymentId = event.gatewayPaymentId();
                String failureReason = event.failureReason();

                String subject = "TRANSACTION ALERT: Payment Failed";
                String message = String.format(
                                "Notice for Account [%s]: Your payment of %s %s could not be completed. "
                                                + "Failure Reason: %s. "
                                                + "Transaction ID: %s | Gateway Reference: %s. "
                                                + "No funds were deducted. Please verify your payment details and retry.",
                                accountNumber, amount, currency, failureReason, transactionId, gatewayPaymentId);

                sendAlert(accountNumber, subject, message);
        }

        private void sendAlert(String accountNumber, String subject, String message) {
                log.info("[NOTIFICATION DISPATCHED] To Account: {} | Subject: {} | Message: {}",
                                accountNumber, subject, message);
        }
}
