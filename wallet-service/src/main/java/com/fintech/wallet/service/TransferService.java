package com.fintech.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.events.TransferFailedEvent;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.model.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferExecutionService transferExecutionService;
    private final OutboxService outboxService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    private static final String TOPIC_WALLET_TRANSFERS = "wallet.transfers";

    public TransferResponse executeTransfer(String idempotencyKey, TransferRequest request) {
        // Step 1: Check Idempotency Key (Atomic Reservation / Replay Guard)
        Optional<IdempotencyRecord> cachedRecord = idempotencyService.startOrCheck(idempotencyKey, request);
        if (cachedRecord.isPresent()) {
            try {
                return objectMapper.readValue(cachedRecord.get().getResponseBody(), TransferResponse.class);
            } catch (Exception e) {
                log.error("Failed to deserialize cached response for key [{}]", idempotencyKey, e);
            }
        }

        try {
            // Step 2: Execute Transfer with Pessimistic Locking & Deadlock Prevention via proxied transaction
            TransferResponse response = transferExecutionService.performTransfer(idempotencyKey, request);

            // Step 3: Mark Idempotency Record as COMPLETED
            idempotencyService.markCompleted(idempotencyKey, 200, response);
            return response;
        } catch (InsufficientFundsException ex) {
            log.warn("Transfer failed due to insufficient funds [Ref: {}]: {}", request.getReferenceId(), ex.getMessage());
            idempotencyService.markFailed(idempotencyKey, 422, ex.getMessage());

            // Enqueue TransferFailedEvent to outbox table
            TransferFailedEvent failedEvent = TransferFailedEvent.builder()
                    .eventId(UUID.randomUUID())
                    .idempotencyKey(idempotencyKey)
                    .referenceId(request.getReferenceId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .failureReason(ex.getMessage())
                    .failedAt(Instant.now())
                    .build();

            try {
                outboxService.enqueueEvent(
                        "WALLET_TRANSFER",
                        request.getReferenceId(),
                        "TransferFailedEvent",
                        TOPIC_WALLET_TRANSFERS,
                        failedEvent
                );
            } catch (Exception e) {
                log.error("Failed to enqueue TransferFailedEvent to outbox", e);
            }

            throw ex;
        } catch (Exception ex) {
            log.warn("Transfer error for reference [{}]: {}", request.getReferenceId(), ex.getMessage());
            idempotencyService.markFailed(idempotencyKey, 400, ex.getMessage());
            throw ex;
        }
    }
}
