package com.fintech.wallet.config;

import com.fintech.wallet.model.OutboxStatus;
import com.fintech.wallet.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository outboxEventRepository;

    private static final long WARNING_THRESHOLD = 200;
    private static final long CRITICAL_THRESHOLD = 1000;

    @Override
    public Health health() {
        long pendingCount = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        long failedCount = outboxEventRepository.countByStatus(OutboxStatus.FAILED);

        Health.Builder builder = (pendingCount > CRITICAL_THRESHOLD) ? Health.down() :
                                 (pendingCount > WARNING_THRESHOLD) ? Health.status("DEGRADED") :
                                 Health.up();

        return builder
                .withDetail("pendingOutboxEvents", pendingCount)
                .withDetail("failedOutboxEvents", failedCount)
                .withDetail("warningThreshold", WARNING_THRESHOLD)
                .withDetail("criticalThreshold", CRITICAL_THRESHOLD)
                .build();
    }
}
