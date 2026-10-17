package com.fintech.wallet.controller;

import com.fintech.wallet.dto.LedgerTransactionDto;
import com.fintech.wallet.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Double-Entry Ledger", description = "Endpoints for inspecting double-entry journal transactions and balanced entries")
public class LedgerController {

    private final LedgerService ledgerService;

    @GetMapping("/transactions/reference/{referenceId}")
    @Operation(summary = "Get double-entry ledger entries by transaction reference ID")
    public ResponseEntity<LedgerTransactionDto> getByReference(@PathVariable String referenceId) {
        return ResponseEntity.ok(ledgerService.getTransactionByReference(referenceId));
    }

    @GetMapping("/transactions/{id}")
    @Operation(summary = "Get double-entry ledger entries by transaction UUID")
    public ResponseEntity<LedgerTransactionDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ledgerService.getTransactionById(id));
    }
}
