package com.fintech.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferCompletedEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId;
    private UUID transactionId;
    private String idempotencyKey;
    private String referenceId;
    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private BigDecimal amount;
    private String currency;
    private String description;
    private Instant completedAt;
}
