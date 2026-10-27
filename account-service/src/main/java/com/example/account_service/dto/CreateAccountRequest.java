package com.example.account_service.dto;

import com.example.account_service.entity.Account;
import com.example.account_service.entity.AccountStatus;
import com.example.account_service.entity.AccountType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    @NotBlank(message = "Account holder name is required")
    private String accountHolderName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Phone is required")
    private String phone;

    @NotNull(message = "Type is required")
    private AccountType type;

    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$")
    private String currency;

    @NotNull(message = "Initial deposit is required")
    @Positive(message = "Initial deposit must be positive")
    private BigDecimal initialDeposit;

    public Account toEntity() {
        Account account = new Account();

        account.setAccountHolderName(this.accountHolderName);
        account.setEmail(this.email);
        account.setPhone(this.phone);
        account.setType(this.type);
        account.setCurrency(this.currency);
        account.setBalance(this.initialDeposit);
        account.setStatus(AccountStatus.ACTIVE);

        return account;
    }
}
