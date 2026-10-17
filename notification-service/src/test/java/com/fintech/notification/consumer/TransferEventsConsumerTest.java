package com.fintech.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.events.TransferCompletedEvent;
import com.fintech.notification.model.NotificationLog;
import com.fintech.notification.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class TransferEventsConsumerTest {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    private TransferEventsConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        consumer = new TransferEventsConsumer(objectMapper, notificationLogRepository);
    }

    @Test
    @DisplayName("Consumer correctly processes TransferCompletedEvent and saves notification log")
    void testConsumeTransferCompletedEvent() throws Exception {
        TransferCompletedEvent event = TransferCompletedEvent.builder()
                .eventId(UUID.randomUUID())
                .transactionId(UUID.randomUUID())
                .idempotencyKey("KEY-12345")
                .referenceId("REF-NOTIFY-999")
                .sourceAccountId(UUID.randomUUID())
                .destinationAccountId(UUID.randomUUID())
                .amount(new BigDecimal("120.50"))
                .currency("USD")
                .description("Payment for services")
                .completedAt(Instant.now())
                .build();

        String jsonPayload = objectMapper.writeValueAsString(event);
        consumer.consumeTransferEvent(jsonPayload);

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());

        NotificationLog saved = captor.getValue();
        assertThat(saved.getReferenceId()).isEqualTo("REF-NOTIFY-999");
        assertThat(saved.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(saved.getStatus()).isEqualTo("DELIVERED");
        assertThat(saved.getMessage()).contains("120.5").contains("USD");
    }
}
