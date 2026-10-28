package com.example.account_service.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

        @ExceptionHandler(AccountNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleAccountNotFound(
                        AccountNotFoundException ex,
                        HttpServletRequest request) {
                return buildErrorResponse(
                                HttpStatus.NOT_FOUND,
                                ex.getMessage(),
                                request.getRequestURI(),
                                Map.of());
        }

        @ExceptionHandler(InsufficientBalanceException.class)
        public ResponseEntity<ErrorResponse> handleInsufficientBalance(
                        InsufficientBalanceException ex,
                        HttpServletRequest request) {
                return buildErrorResponse(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                ex.getMessage(),
                                request.getRequestURI(),
                                Map.of());
        }

        @ExceptionHandler(AccountInactiveException.class)
        public ResponseEntity<ErrorResponse> handleAccountInactive(
                        AccountInactiveException ex,
                        HttpServletRequest request) {
                return buildErrorResponse(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                ex.getMessage(),
                                request.getRequestURI(),
                                Map.of());
        }

        @ExceptionHandler(DailyLimitExceededException.class)
        public ResponseEntity<ErrorResponse> handleDailyLimitExceeded(
                        DailyLimitExceededException ex,
                        HttpServletRequest request) {
                return buildErrorResponse(
                                HttpStatus.UNPROCESSABLE_CONTENT,
                                ex.getMessage(),
                                request.getRequestURI(),
                                Map.of());
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ErrorResponse> handleIllegalArgument(
                        IllegalArgumentException ex, HttpServletRequest request) {
                return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), Map.of());
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> handleValidationErrors(
                        MethodArgumentNotValidException ex,
                        HttpServletRequest request) {
                Map<String, String> details = new LinkedHashMap<>();

                for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
                        details.putIfAbsent(
                                        fieldError.getField(),
                                        fieldError.getDefaultMessage() != null
                                                        ? fieldError.getDefaultMessage()
                                                        : "Invalid value");
                }

                return buildErrorResponse(
                                HttpStatus.BAD_REQUEST,
                                "Request validation failed",
                                request.getRequestURI(),
                                details);
        }

        @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
        public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
                        ObjectOptimisticLockingFailureException ex,
                        HttpServletRequest request) {
                log.warn("Optimistic lock conflict on path {}: {}", request.getRequestURI(), ex.getMessage());
                return buildErrorResponse(
                                HttpStatus.CONFLICT,
                                "Account was modified by another transaction. Please retry.",
                                request.getRequestURI(),
                                Map.of());
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleUnexpectedException(
                        Exception ex,
                        HttpServletRequest request) {
                log.error("Unhandled exception occurred on path {}: ", request.getRequestURI(), ex);
                return buildErrorResponse(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "An unexpected error occurred",
                                request.getRequestURI(),
                                Map.of());
        }

        private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String message, String path,
                        Map<String, String> details) {
                ErrorResponse errorResponse = new ErrorResponse(
                                LocalDateTime.now(),
                                status.value(),
                                status.getReasonPhrase(),
                                message,
                                path,
                                details);

                return ResponseEntity.status(status).body(errorResponse);
        }
}
