package com.fintech.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.exception.IdempotencyConflictException;
import com.fintech.wallet.model.IdempotencyRecord;
import com.fintech.wallet.model.IdempotencyStatus;
import com.fintech.wallet.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    /**
     * Stalled in-progress timeout. If a node crashes mid-transaction, allow retry after 2 minutes.
     */
    private static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Generates a deterministic SHA-256 hash from canonical JSON representation.
     */
    public String computeHash(Object payload) {
        try {
            JsonNode tree = objectMapper.valueToTree(payload);
            String canonicalJson = objectMapper.writeValueAsString(tree);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to calculate deterministic idempotency hash", e);
        }
    }

    /**
     * Attempts to acquire an idempotency lock for this key.
     * Uses REQUIRES_NEW so the lock is committed independently.
     * Returns an existing completed record if already processed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> startOrCheck(String key, Object requestPayload) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be null or blank");
        }

        String requestHash = computeHash(requestPayload);
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(key);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // Production Rule 1: Key Reuse with Differing Payloads Detection (Stripe Standard)
            if (!record.getRequestHash().equals(requestHash)) {
                log.warn("Idempotency key [{}] reused with different request payload hash", key);
                throw new IdempotencyConflictException(String.format(
                        "Idempotency key '%s' was previously used with different request parameters.", key));
            }

            // Production Rule 2: Cached Response Delivery
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                log.info("Idempotent request detected for key [{}], returning cached response", key);
                return Optional.of(record);
            }

            // Production Rule 3: Crash Recovery / Stalled Lease Check
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                Instant now = Instant.now();
                if (record.getCreatedAt() != null && Duration.between(record.getCreatedAt(), now).compareTo(IN_PROGRESS_TIMEOUT) > 0) {
                    log.warn("Idempotency key [{}] lease expired (> {}). Reclaiming lock for retry.", key, IN_PROGRESS_TIMEOUT);
                    record.setUpdatedAt(now);
                    idempotencyRecordRepository.save(record);
                    return Optional.empty(); // allow retry
                }
                throw new IdempotencyConflictException(
                        "A concurrent transfer with idempotency key '" + key + "' is currently in progress. Please retry shortly.");
            }

            throw new IdempotencyConflictException(
                    "Previous operation for idempotency key '" + key + "' failed: " + record.getResponseBody());
        }

        // Atomically reserve the key
        try {
            IdempotencyRecord newRecord = IdempotencyRecord.builder()
                    .idempotencyKey(key)
                    .requestHash(requestHash)
                    .status(IdempotencyStatus.IN_PROGRESS)
                    .build();
            idempotencyRecordRepository.saveAndFlush(newRecord);
            return Optional.empty(); // Proceed with execution
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition on idempotency key [{}]: another concurrent thread acquired reservation", key);
            throw new IdempotencyConflictException(
                    "Concurrent transfer request detected for idempotency key '" + key + "'.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String key, int responseCode, Object responsePayload) {
        try {
            String bodyJson = objectMapper.writeValueAsString(responsePayload);
            idempotencyRecordRepository.findById(key).ifPresent(record -> {
                record.setStatus(IdempotencyStatus.COMPLETED);
                record.setResponseCode(responseCode);
                record.setResponseBody(bodyJson);
                idempotencyRecordRepository.save(record);
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response payload for idempotency key [{}]", key, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String key, int responseCode, String errorMessage) {
        idempotencyRecordRepository.findById(key).ifPresent(record -> {
            record.setStatus(IdempotencyStatus.FAILED);
            record.setResponseCode(responseCode);
            record.setResponseBody(errorMessage);
            idempotencyRecordRepository.save(record);
        });
    }
}
