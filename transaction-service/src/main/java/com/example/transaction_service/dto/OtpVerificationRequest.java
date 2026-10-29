package com.example.transaction_service.dto;

import jakarta.validation.constraints.NotBlank;

public record OtpVerificationRequest(
        @NotBlank String transactionId,

        @NotBlank String otp) {
}
