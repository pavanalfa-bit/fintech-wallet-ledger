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
public class TransferFailedEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID eventId;
    private String idempotencyKey;
    private String referenceId;
    private UUID sourceAccountId;
    private UUID destinationAccountId;
    private BigDecimal amount;
    private String currency;
    private String failureReason;
    private Instant failedAt;
}
