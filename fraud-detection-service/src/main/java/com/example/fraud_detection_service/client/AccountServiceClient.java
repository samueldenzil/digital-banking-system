package com.example.fraud_detection_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.fraud_detection_service.client.dto.AccountResponse;

@FeignClient(name = "account-service", url = "${application.clients.account-service.url:http://localhost:8081}")
public interface AccountServiceClient {

    @GetMapping("/api/v1/accounts/{accountNumber}")
    AccountResponse getAccount(@PathVariable String accountNumber);
}
