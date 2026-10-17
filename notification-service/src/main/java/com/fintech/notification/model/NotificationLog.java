package com.fintech.notification.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "notification_logs",
    indexes = {
        @Index(name = "idx_notify_event_id", columnList = "event_id", unique = true),
        @Index(name = "idx_notify_ref_id", columnList = "reference_id"),
        @Index(name = "idx_notify_sent_at", columnList = "sent_at")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "reference_id", nullable = false, length = 128)
    private String referenceId;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;
}
