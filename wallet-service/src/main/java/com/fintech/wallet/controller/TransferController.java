package com.fintech.wallet.controller;

import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers & Payments", description = "Endpoints for idempotent double-entry balance transfers")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    @Operation(summary = "Execute an idempotent wallet-to-wallet transfer")
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "Unique client-generated idempotency key (UUID)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferResponse response = transferService.executeTransfer(idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
