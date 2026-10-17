package com.fintech.notification.repository;

import com.fintech.notification.model.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    List<NotificationLog> findByReferenceIdOrderBySentAtDesc(String referenceId);
    List<NotificationLog> findTop50ByOrderBySentAtDesc();
}
