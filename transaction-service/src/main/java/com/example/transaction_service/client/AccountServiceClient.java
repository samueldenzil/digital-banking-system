package com.example.transaction_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.example.transaction_service.client.dto.AccountResponse;
import com.example.transaction_service.client.dto.TransactionAmountRequest;

@FeignClient(name = "account-service", url = "${application.clients.account-service.url:http://localhost:8081}")
public interface AccountServiceClient {

    @GetMapping("/api/v1/accounts/{accountNumber}")
    AccountResponse getAccount(@PathVariable String accountNumber);

    @PutMapping("/api/v1/accounts/{accountNumber}/deduct")
    AccountResponse deductBalance(@PathVariable String accountNumber, @RequestBody TransactionAmountRequest request);

    @PutMapping("/api/v1/accounts/{accountNumber}/credit")
    AccountResponse creditBalance(@PathVariable String accountNumber, @RequestBody TransactionAmountRequest request);

    @PutMapping("/api/v1/accounts/{accountNumber}/refund")
    AccountResponse refundBalance(@PathVariable String accountNumber, @RequestBody TransactionAmountRequest request);
}
