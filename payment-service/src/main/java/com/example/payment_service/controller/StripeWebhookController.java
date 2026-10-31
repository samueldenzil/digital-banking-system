package com.example.payment_service.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.payment_service.service.PaymentService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private final PaymentService paymentService;

    @Value("${stripe.webhook-secret}")
    private String endpointSecret;

    @PostMapping
    public ResponseEntity<String> handleStripeWebhook(@RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {
        if (sigHeader == null) {
            log.warn("Missing Stripe-Signature header in webhook request.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing signature");
        }

        Event event;

        try {
            // 1. Verify the cryptographic signature using Stripe SDK
            event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
        } catch (SignatureVerificationException e) {
            log.error("Invalid Stripe signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch (Exception e) {
            log.error("Webhook processing error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Webhook error");
        }

        // 2. Handle specific Stripe event types
        log.info("Received Stripe Webhook event: {}", event.getType());

        switch (event.getType()) {
            case "payment_intent.succeeded" -> {
                // Deserialize the event object into a PaymentIntent
                PaymentIntent paymentIntent = (PaymentIntent) event.getDataObjectDeserializer().getObject()
                        .orElse(null);

                if (paymentIntent != null) {
                    log.info("Payment succeeded for Intent: {}", paymentIntent.getId());
                    paymentService.confirmPayment(paymentIntent.getId());
                }
            }

            case "payment_intent.payment_failed" -> {
                // Deserialize the event object into a PaymentIntent
                PaymentIntent paymentIntent = (PaymentIntent) event.getDataObjectDeserializer().getObject()
                        .orElse(null);

                if (paymentIntent != null) {
                    log.warn("Payment failed for Intent: {}", paymentIntent.getId());

                    String failureMessage = paymentIntent.getLastPaymentError() != null
                            ? paymentIntent.getLastPaymentError().getMessage()
                            : "Payment failed";

                    paymentService.failPayment(paymentIntent.getId(), failureMessage);
                }
            }

            default -> log.debug("Unhandled event type; {}", event.getType());
        }

        // 3. Always return 200 OK so Stripe knows the event was successfully received
        return ResponseEntity.ok("Received");
    }
}
