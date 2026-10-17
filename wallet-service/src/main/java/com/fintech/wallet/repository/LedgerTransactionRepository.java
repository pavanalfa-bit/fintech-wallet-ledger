package com.fintech.wallet.repository;

import com.fintech.wallet.model.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Optional<LedgerTransaction> findByReferenceId(String referenceId);
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
}
