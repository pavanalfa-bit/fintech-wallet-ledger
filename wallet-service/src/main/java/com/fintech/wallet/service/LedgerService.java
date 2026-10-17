package com.fintech.wallet.service;

import com.fintech.wallet.dto.LedgerTransactionDto;
import com.fintech.wallet.exception.LedgerInvariantException;
import com.fintech.wallet.model.*;
import com.fintech.wallet.repository.LedgerEntryRepository;
import com.fintech.wallet.repository.LedgerTransactionRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    @Data
    @Builder
    public static class EntryDraft {
        private Account account;
        private EntryType entryType;
        private BigDecimal amount;
        private String currency;
    }

    /**
     * Records an atomic double-entry ledger transaction.
     * Enforces the fundamental accounting equation: sum(DEBIT) == sum(CREDIT).
     */
    @Transactional
    public LedgerTransaction recordTransaction(
            String referenceId,
            String idempotencyKey,
            TransactionType transactionType,
            String description,
            List<EntryDraft> entryDrafts
    ) {
        if (entryDrafts == null || entryDrafts.size() < 2) {
            throw new LedgerInvariantException("A double-entry transaction must contain at least 2 entries");
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        String currency = entryDrafts.get(0).getCurrency();

        for (EntryDraft draft : entryDrafts) {
            if (draft.getAmount() == null || draft.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new LedgerInvariantException("Entry amount must be strictly positive");
            }
            if (!currency.equalsIgnoreCase(draft.getCurrency())) {
                throw new LedgerInvariantException("Multi-currency ledger transactions are not permitted within a single atomic post");
            }

            if (draft.getEntryType() == EntryType.DEBIT) {
                totalDebits = totalDebits.add(draft.getAmount());
            } else if (draft.getEntryType() == EntryType.CREDIT) {
                totalCredits = totalCredits.add(draft.getAmount());
            } else {
                throw new LedgerInvariantException("Unsupported entry type: " + draft.getEntryType());
            }
        }

        // CRITICAL DOUBLE-ENTRY INVARIANT CHECK
        if (totalDebits.compareTo(totalCredits) != 0) {
            log.error("Ledger invariant violation: Total Debits [{}] != Total Credits [{}] for reference [{}]",
                    totalDebits, totalCredits, referenceId);
            throw new LedgerInvariantException(String.format(
                    "Ledger imbalance: Debits (%s %s) do not equal Credits (%s %s)",
                    totalDebits.toPlainString(), currency, totalCredits.toPlainString(), currency
            ));
        }

        LedgerTransaction transaction = LedgerTransaction.builder()
                .referenceId(referenceId)
                .idempotencyKey(idempotencyKey)
                .transactionType(transactionType)
                .description(description)
                .status(TransactionStatus.POSTED)
                .build();

        transaction = transactionRepository.save(transaction);

        for (EntryDraft draft : entryDrafts) {
            LedgerEntry entry = LedgerEntry.builder()
                    .transaction(transaction)
                    .account(draft.getAccount())
                    .entryType(draft.getEntryType())
                    .amount(draft.getAmount())
                    .currency(currency.toUpperCase())
                    .build();
            entryRepository.save(entry);
            transaction.addEntry(entry);
        }

        log.info("Ledger transaction [{}] posted successfully for reference [{}]. Total: {} {}",
                transaction.getId(), referenceId, totalDebits.toPlainString(), currency);

        return transaction;
    }

    @Transactional(readOnly = true)
    public LedgerTransactionDto getTransactionByReference(String referenceId) {
        LedgerTransaction tx = transactionRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger transaction not found for reference: " + referenceId));

        return toDto(tx);
    }

    @Transactional(readOnly = true)
    public LedgerTransactionDto getTransactionById(UUID id) {
        LedgerTransaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ledger transaction not found for id: " + id));

        return toDto(tx);
    }

    private LedgerTransactionDto toDto(LedgerTransaction tx) {
        List<LedgerTransactionDto.LedgerEntryDto> entryDtos = tx.getEntries().stream()
                .map(e -> LedgerTransactionDto.LedgerEntryDto.builder()
                        .entryId(e.getId())
                        .accountNumber(e.getAccount().getAccountNumber())
                        .entryType(e.getEntryType())
                        .amount(e.getAmount())
                        .currency(e.getCurrency())
                        .build())
                .collect(Collectors.toList());

        return LedgerTransactionDto.builder()
                .transactionId(tx.getId())
                .referenceId(tx.getReferenceId())
                .idempotencyKey(tx.getIdempotencyKey())
                .transactionType(tx.getTransactionType())
                .status(tx.getStatus())
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt())
                .entries(entryDtos)
                .build();
    }
}
