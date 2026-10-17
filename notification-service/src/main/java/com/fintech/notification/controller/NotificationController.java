package com.fintech.notification.controller;

import com.fintech.notification.model.NotificationLog;
import com.fintech.notification.repository.NotificationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Endpoints to view dispatched transfer event notifications")
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;

    @GetMapping
    @Operation(summary = "List latest notifications dispatched by Kafka consumer")
    public ResponseEntity<List<NotificationLog>> getRecentNotifications() {
        return ResponseEntity.ok(notificationLogRepository.findTop50ByOrderBySentAtDesc());
    }

    @GetMapping("/reference/{referenceId}")
    @Operation(summary = "Get notification logs for a specific transfer reference")
    public ResponseEntity<List<NotificationLog>> getByReference(@PathVariable String referenceId) {
        return ResponseEntity.ok(notificationLogRepository.findByReferenceIdOrderBySentAtDesc(referenceId));
    }
}
