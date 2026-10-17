package com.fintech.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.events.TransferCompletedEvent;
import com.fintech.events.TransferFailedEvent;
import com.fintech.notification.model.NotificationLog;
import com.fintech.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferEventsConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationLogRepository notificationLogRepository;

    @KafkaListener(
            topics = "${fintech.kafka.topics.transfers:wallet.transfers}",
            groupId = "${spring.kafka.consumer.group-id:notification-service-group}"
    )
    public void consumeTransferEvent(String messagePayload) {
        try {
            JsonNode root = objectMapper.readTree(messagePayload);

            if (root.has("completedAt")) {
                TransferCompletedEvent event = objectMapper.treeToValue(root, TransferCompletedEvent.class);
                handleTransferCompleted(event);
            } else if (root.has("failedAt")) {
                TransferFailedEvent event = objectMapper.treeToValue(root, TransferFailedEvent.class);
                handleTransferFailed(event);
            } else {
                log.warn("Unknown event format received on Kafka: {}", messagePayload);
            }
        } catch (Exception ex) {
            log.error("Failed to process Kafka message: {}", messagePayload, ex);
        }
    }

    private void handleTransferCompleted(TransferCompletedEvent event) {
        // Idempotent consumer check
        if (event.getEventId() != null && notificationLogRepository.findByEventId(event.getEventId()).isPresent()) {
            log.info("Duplicate event [{}] skipped by consumer (idempotent listener)", event.getEventId());
            return;
        }

        log.info(">>> NOTIFICATION SENT: Transfer SUCCESS [Ref: {}]. Transferred {} {} from account {} to account {}",
                event.getReferenceId(),
                event.getAmount(),
                event.getCurrency(),
                event.getSourceAccountId(),
                event.getDestinationAccountId());

        NotificationLog logEntry = NotificationLog.builder()
                .eventId(event.getEventId())
                .eventType("TRANSFER_COMPLETED")
                .referenceId(event.getReferenceId())
                .channel("EMAIL_AND_PUSH")
                .status("DELIVERED")
                .message(String.format("Transfer of %s %s completed successfully. Description: %s",
                        event.getAmount(), event.getCurrency(), event.getDescription()))
                .build();

        try {
            notificationLogRepository.save(logEntry);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Event [{}] was concurrently recorded by another consumer instance", event.getEventId());
        }
    }

    private void handleTransferFailed(TransferFailedEvent event) {
        // Idempotent consumer check
        if (event.getEventId() != null && notificationLogRepository.findByEventId(event.getEventId()).isPresent()) {
            log.info("Duplicate event [{}] skipped by consumer (idempotent listener)", event.getEventId());
            return;
        }

        log.warn(">>> NOTIFICATION SENT: Transfer FAILED [Ref: {}]. Reason: {}",
                event.getReferenceId(), event.getFailureReason());

        NotificationLog logEntry = NotificationLog.builder()
                .eventId(event.getEventId())
                .eventType("TRANSFER_FAILED")
                .referenceId(event.getReferenceId())
                .channel("EMAIL_AND_PUSH")
                .status("DELIVERED")
                .message(String.format("Transfer of %s %s failed. Reason: %s",
                        event.getAmount(), event.getCurrency(), event.getFailureReason()))
                .build();

        try {
            notificationLogRepository.save(logEntry);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Event [{}] was concurrently recorded by another consumer instance", event.getEventId());
        }
    }
}
