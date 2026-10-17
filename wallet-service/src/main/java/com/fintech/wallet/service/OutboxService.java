package com.fintech.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.model.OutboxEvent;
import com.fintech.wallet.model.OutboxStatus;
import com.fintech.wallet.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Enqueues an event to the outbox table within the caller's active database transaction.
     * Guarantees atomic commitment alongside ledger entries.
     */
    @Transactional
    public OutboxEvent enqueueEvent(String aggregateType, String aggregateId, String eventType, String topic, Object eventPayload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

            OutboxEvent saved = outboxEventRepository.save(outboxEvent);
            log.info("Outbox event [{}] enqueued for aggregate [{}:{}]", saved.getId(), aggregateType, aggregateId);
            return saved;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload for [{}:{}]", aggregateType, aggregateId, e);
            throw new RuntimeException("Serialization error for outbox event", e);
        }
    }
}
