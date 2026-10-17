package com.fintech.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public String computeHash(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Error computing idempotency hash", e);
        }
    }

    /**
     * Attempts to acquire an idempotency lock for this key.
     * Uses REQUIRES_NEW so the lock is committed independently.
     * Returns an existing completed record if already processed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> startOrCheck(String key, Object requestPayload) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        String requestHash = computeHash(requestPayload);

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                log.info("Idempotent request detected for key [{}], returning cached response", key);
                return Optional.of(record);
            }
            if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                throw new IdempotencyConflictException("Concurrent request in progress for idempotency key: " + key);
            }
            throw new IdempotencyConflictException("Previous operation for idempotency key [" + key + "] failed: " + record.getResponseBody());
        }

        // Create new in-progress record
        try {
            IdempotencyRecord newRecord = IdempotencyRecord.builder()
                    .idempotencyKey(key)
                    .requestHash(requestHash)
                    .status(IdempotencyStatus.IN_PROGRESS)
                    .build();
            idempotencyRecordRepository.saveAndFlush(newRecord);
            return Optional.empty(); // proceed with execution
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition on idempotency key [{}]: another thread inserted it first", key);
            throw new IdempotencyConflictException("Concurrent request detected for idempotency key: " + key);
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
