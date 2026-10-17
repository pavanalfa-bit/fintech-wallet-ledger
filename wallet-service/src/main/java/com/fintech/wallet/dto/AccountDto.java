package com.fintech.wallet.dto;

import com.fintech.wallet.model.AccountStatus;
import com.fintech.wallet.model.AccountType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountDto {
    private UUID id;
    private String accountNumber;
    private String userId;
    private AccountType accountType;
    private String currency;
    private BigDecimal balance;
    private AccountStatus status;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}
