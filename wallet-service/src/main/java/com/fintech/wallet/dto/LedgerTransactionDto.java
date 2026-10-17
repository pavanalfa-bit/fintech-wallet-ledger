package com.fintech.wallet.dto;

import com.fintech.wallet.model.EntryType;
import com.fintech.wallet.model.TransactionStatus;
import com.fintech.wallet.model.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerTransactionDto {
    private UUID transactionId;
    private String referenceId;
    private String idempotencyKey;
    private TransactionType transactionType;
    private TransactionStatus status;
    private String description;
    private Instant createdAt;
    private List<LedgerEntryDto> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LedgerEntryDto {
        private UUID entryId;
        private String accountNumber;
        private EntryType entryType;
        private BigDecimal amount;
        private String currency;
    }
}
