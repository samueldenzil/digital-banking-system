package com.example.transaction_service.exception;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        when(request.getRequestURI()).thenReturn("/api/v1/transactions");
    }

    @Test
    @DisplayName("IllegalArgumentException maps to 400 Bad Request")
    void handleIllegalArgumentShouldReturnBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Source account not found: 12345");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalArgument(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("Source account not found: 12345");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/transactions");
    }

    @Test
    @DisplayName("IllegalStateException maps to 503 Service Unavailable")
    void handleIllegalStateShouldReturnServiceUnavailable() {
        IllegalStateException ex = new IllegalStateException("Account service is temporarily unavailable. Please try again later.");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalState(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(503);
        assertThat(response.getBody().error()).isEqualTo("Service Unavailable");
        assertThat(response.getBody().message()).contains("temporarily unavailable");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException maps to 400 Bad Request with field errors")
    void handleValidationErrorsShouldReturnBadRequestWithDetails() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("transactionRequest", "amount", "Transfer amount must be greater than zero");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleValidationErrors(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        assertThat(response.getBody().details()).containsEntry("amount", "Transfer amount must be greater than zero");
    }

    @Test
    @DisplayName("DataIntegrityViolationException maps to 409 Conflict")
    void handleDataIntegrityViolationShouldReturnConflict() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("Duplicate key");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleDataIntegrityViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("Conflict");
    }

    @Test
    @DisplayName("FeignException (404) maps to 404 Not Found")
    void handleFeignExceptionNotFoundShouldReturnNotFound() {
        FeignException ex = mock(FeignException.class);
        when(ex.status()).thenReturn(404);

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleFeignException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("Requested downstream resource was not found");
    }

    @Test
    @DisplayName("FeignException (500) maps to 503 Service Unavailable")
    void handleFeignExceptionServerErrorShouldReturnServiceUnavailable() {
        FeignException ex = mock(FeignException.class);
        when(ex.status()).thenReturn(500);

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleFeignException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(503);
    }

    @Test
    @DisplayName("Unexpected Exception maps to 500 Internal Server Error")
    void handleUnexpectedExceptionShouldReturnInternalServerError() {
        RuntimeException ex = new RuntimeException("Unexpected NPE or system crash");

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleUnexpectedException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }
}

