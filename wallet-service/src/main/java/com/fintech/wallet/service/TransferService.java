package com.fintech.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.events.TransferCompletedEvent;
import com.fintech.events.TransferFailedEvent;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.exception.AccountNotFoundException;
import com.fintech.wallet.exception.CurrencyMismatchException;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.model.*;
import com.fintech.wallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
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
            // Step 2: Execute Transfer with Pessimistic Locking & Deadlock Prevention
            TransferResponse response = performTransferTransaction(idempotencyKey, request);

            // Step 3: Mark Idempotency Record as COMPLETED
            idempotencyService.markCompleted(idempotencyKey, 200, response);
            return response;
        } catch (Exception ex) {
            log.warn("Transfer failed for reference [{}]: {}", request.getReferenceId(), ex.getMessage());
            idempotencyService.markFailed(idempotencyKey, 400, ex.getMessage());
            throw ex;
        }
    }

    @Transactional
    protected TransferResponse performTransferTransaction(String idempotencyKey, TransferRequest request) {
        if (request.getSourceAccountNumber().equals(request.getDestinationAccountNumber())) {
            throw new IllegalArgumentException("Source and destination accounts must be distinct");
        }

        // Deadlock Prevention: Deterministic Lock Order (Lexicographical Account Number sorting)
        String firstAccNum = request.getSourceAccountNumber().compareTo(request.getDestinationAccountNumber()) < 0
                ? request.getSourceAccountNumber()
                : request.getDestinationAccountNumber();

        String secondAccNum = request.getSourceAccountNumber().compareTo(request.getDestinationAccountNumber()) < 0
                ? request.getDestinationAccountNumber()
                : request.getSourceAccountNumber();

        Account lockedFirst = accountRepository.findByAccountNumberForUpdate(firstAccNum)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + firstAccNum));

        Account lockedSecond = accountRepository.findByAccountNumberForUpdate(secondAccNum)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + secondAccNum));

        Account sourceAccount = lockedFirst.getAccountNumber().equals(request.getSourceAccountNumber())
                ? lockedFirst : lockedSecond;
        Account destAccount = lockedFirst.getAccountNumber().equals(request.getDestinationAccountNumber())
                ? lockedFirst : lockedSecond;

        // Currency Validation
        if (!sourceAccount.getCurrency().equalsIgnoreCase(request.getCurrency()) ||
            !destAccount.getCurrency().equalsIgnoreCase(request.getCurrency())) {
            throw new CurrencyMismatchException(String.format(
                    "Currency mismatch. Transfer requires %s, but source is %s and destination is %s",
                    request.getCurrency(), sourceAccount.getCurrency(), destAccount.getCurrency()
            ));
        }

        // Concurrency Control & Balance Check
        if (!sourceAccount.hasSufficientBalance(request.getAmount())) {
            // Record failure in Outbox for asynchronous notification dispatch
            TransferFailedEvent failedEvent = TransferFailedEvent.builder()
                    .eventId(UUID.randomUUID())
                    .idempotencyKey(idempotencyKey)
                    .referenceId(request.getReferenceId())
                    .sourceAccountId(sourceAccount.getId())
                    .destinationAccountId(destAccount.getId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .failureReason("Insufficient funds: current balance " + sourceAccount.getBalance())
                    .failedAt(Instant.now())
                    .build();

            outboxService.enqueueEvent(
                    "WALLET_TRANSFER",
                    request.getReferenceId(),
                    "TransferFailedEvent",
                    TOPIC_WALLET_TRANSFERS,
                    failedEvent
            );

            throw new InsufficientFundsException(String.format(
                    "Insufficient funds in account %s. Requested: %s %s, Available: %s %s",
                    sourceAccount.getAccountNumber(),
                    request.getAmount().toPlainString(),
                    request.getCurrency(),
                    sourceAccount.getBalance().toPlainString(),
                    sourceAccount.getCurrency()
            ));
        }

        // Apply Double-Entry Accounting Balances
        // Customer Wallets are Liabilities (Credit normal):
        // Debiting source decreases customer liability (balance).
        // Crediting destination increases customer liability (balance).
        sourceAccount.applyEntry(EntryType.DEBIT, request.getAmount());
        destAccount.applyEntry(EntryType.CREDIT, request.getAmount());

        accountRepository.save(sourceAccount);
        accountRepository.save(destAccount);

        // Record Double-Entry Ledger Transaction & Verify Invariant sum(Debit) == sum(Credit)
        List<LedgerService.EntryDraft> entryDrafts = List.of(
                LedgerService.EntryDraft.builder()
                        .account(sourceAccount)
                        .entryType(EntryType.DEBIT)
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .build(),
                LedgerService.EntryDraft.builder()
                        .account(destAccount)
                        .entryType(EntryType.CREDIT)
                        .amount(request.getAmount())
                        .currency(request.getCurrency())
                        .build()
        );

        LedgerTransaction ledgerTx = ledgerService.recordTransaction(
                request.getReferenceId(),
                idempotencyKey,
                TransactionType.TRANSFER,
                request.getDescription() != null ? request.getDescription() : "Transfer from " + sourceAccount.getAccountNumber() + " to " + destAccount.getAccountNumber(),
                entryDrafts
        );

        // Outbox Pattern: Publish TransferCompletedEvent within the exact same DB transaction
        TransferCompletedEvent completedEvent = TransferCompletedEvent.builder()
                .eventId(UUID.randomUUID())
                .transactionId(ledgerTx.getId())
                .idempotencyKey(idempotencyKey)
                .referenceId(request.getReferenceId())
                .sourceAccountId(sourceAccount.getId())
                .destinationAccountId(destAccount.getId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .description(ledgerTx.getDescription())
                .completedAt(ledgerTx.getCreatedAt())
                .build();

        outboxService.enqueueEvent(
                "WALLET_TRANSFER",
                ledgerTx.getId().toString(),
                "TransferCompletedEvent",
                TOPIC_WALLET_TRANSFERS,
                completedEvent
        );

        return TransferResponse.builder()
                .transactionId(ledgerTx.getId())
                .referenceId(request.getReferenceId())
                .idempotencyKey(idempotencyKey)
                .status(TransactionStatus.POSTED)
                .sourceAccountNumber(sourceAccount.getAccountNumber())
                .destinationAccountNumber(destAccount.getAccountNumber())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .description(ledgerTx.getDescription())
                .createdAt(ledgerTx.getCreatedAt())
                .build();
    }
}
