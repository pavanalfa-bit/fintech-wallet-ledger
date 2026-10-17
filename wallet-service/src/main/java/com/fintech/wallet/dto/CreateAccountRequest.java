package com.fintech.wallet.dto;

import com.fintech.wallet.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotNull(message = "accountType is required")
    private AccountType accountType;

    @NotBlank(message = "currency is required")
    private String currency;

    @PositiveOrZero(message = "initialBalance must be non-negative")
    @Builder.Default
    private BigDecimal initialBalance = BigDecimal.ZERO;
}
