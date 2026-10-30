package com.example.fraud_detection_service.model;

public record FraudCheckResult(boolean isFraud, String reason) {

    public static FraudCheckResult clean() {
        return new FraudCheckResult(false, null);
    }

    public static FraudCheckResult fraud(String reason) {
        return new FraudCheckResult(true, reason);
    }
}
