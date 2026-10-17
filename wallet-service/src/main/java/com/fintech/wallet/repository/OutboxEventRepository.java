package com.fintech.wallet.repository;

import com.fintech.wallet.model.OutboxEvent;
import com.fintech.wallet.model.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Retrieves pending outbox events using pessimistic locking with SKIP LOCKED hint (-2 in Hibernate).
     * This guarantees horizontal scalability across multiple service replicas without duplicate event processing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status AND o.retryCount < :maxRetries ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingOrRetryableEvents(
            @Param("status") OutboxStatus status,
            @Param("maxRetries") int maxRetries,
            Pageable pageable
    );

    long countByStatus(OutboxStatus status);
}
