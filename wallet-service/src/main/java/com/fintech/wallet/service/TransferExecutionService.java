package com.fintech.wallet.service;

import com.fintech.events.TransferCompletedEvent;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.exception.AccountNotFoundException;
import com.fintech.wallet.exception.CurrencyMismatchException;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.model.Account;
import com.fintech.wallet.model.EntryType;
import com.fintech.wallet.model.LedgerTransaction;
import com.fintech.wallet.model.TransactionStatus;
import com.fintech.wallet.model.TransactionType;
import com.fintech.wallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferExecutionService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;

    private static final String TOPIC_WALLET_TRANSFERS = "wallet.transfers";

    @Transactional
    public TransferResponse performTransfer(String idempotencyKey, TransferRequest request) {
        if (request.getSourceAccountNumber().equals(request.getDestinationAccountNumber())) {
            throw new IllegalArgumentException("Source and destination accounts must be distinct");
        }

        // Latency Optimization & Deadlock Prevention: Single round-trip batch lock ordered lexicographically
        List<Account> lockedAccounts = accountRepository.findBothByAccountNumbersForUpdate(
                request.getSourceAccountNumber(), request.getDestinationAccountNumber());

        if (lockedAccounts.size() != 2) {
            throw new AccountNotFoundException(String.format(
                    "One or both accounts not found or inactive: %s, %s",
                    request.getSourceAccountNumber(), request.getDestinationAccountNumber()));
        }

        Account sourceAccount = lockedAccounts.get(0).getAccountNumber().equals(request.getSourceAccountNumber())
                ? lockedAccounts.get(0) : lockedAccounts.get(1);
        Account destAccount = lockedAccounts.get(0).getAccountNumber().equals(request.getDestinationAccountNumber())
                ? lockedAccounts.get(0) : lockedAccounts.get(1);

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
