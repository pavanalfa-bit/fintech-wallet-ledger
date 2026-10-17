package com.fintech.wallet.service;

import com.fintech.wallet.model.OutboxEvent;
import com.fintech.wallet.model.OutboxStatus;
import com.fintech.wallet.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fintech.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 5;

    @Scheduled(fixedDelayString = "${fintech.outbox.publisher.interval-ms:1000}")
    public void processOutboxEvents() {
        List<UUID> pendingEventIds = fetchPendingBatchIds();
        if (pendingEventIds.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox event(s) to dispatch", pendingEventIds.size());

        for (UUID eventId : pendingEventIds) {
            publishSingleEvent(eventId);
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> fetchPendingBatchIds() {
        return outboxEventRepository.findPendingOrRetryableEvents(
                OutboxStatus.PENDING,
                MAX_RETRIES,
                PageRequest.of(0, BATCH_SIZE)
        ).stream().map(OutboxEvent::getId).collect(Collectors.toList());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSingleEvent(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() == OutboxStatus.PUBLISHED) {
            return;
        }

        try {
            // Synchronous delivery verification with 5s timeout
            kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setProcessedAt(Instant.now());
            event.setErrorMessage(null);
            outboxEventRepository.save(event);
            log.info("Outbox event [{}] successfully published to Kafka topic [{}]", event.getId(), event.getTopic());
        } catch (Exception ex) {
            log.warn("Failed to publish outbox event [{}] to topic [{}]: {}",
                    event.getId(), event.getTopic(), ex.getMessage());
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setErrorMessage(ex.getMessage());
            if (retries >= MAX_RETRIES) {
                event.setStatus(OutboxStatus.FAILED);
                log.error("Outbox event [{}] reached maximum retry count ({}) and marked FAILED",
                        event.getId(), MAX_RETRIES);
            }
            outboxEventRepository.save(event);
        }
    }
}
