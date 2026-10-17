package com.fintech.wallet.controller;

import com.fintech.wallet.dto.AccountDto;
import com.fintech.wallet.dto.CreateAccountRequest;
import com.fintech.wallet.dto.DepositRequest;
import com.fintech.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
@Tag(name = "Wallet Management", description = "Endpoints for creating accounts and funding balances")
public class WalletController {

    private final WalletService walletService;

    @PostMapping
    @Operation(summary = "Create a new wallet account")
    public ResponseEntity<AccountDto> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountDto created = walletService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Get wallet account details by account number")
    public ResponseEntity<AccountDto> getAccount(@PathVariable String accountNumber) {
        return ResponseEntity.ok(walletService.getAccountByNumber(accountNumber));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get all wallet accounts for a user")
    public ResponseEntity<List<AccountDto>> getAccountsByUser(@PathVariable String userId) {
        return ResponseEntity.ok(walletService.getAccountsByUserId(userId));
    }

    @PostMapping("/deposit")
    @Operation(summary = "Deposit funds into an account from platform clearing")
    public ResponseEntity<AccountDto> deposit(@Valid @RequestBody DepositRequest request) {
        return ResponseEntity.ok(walletService.deposit(request));
    }
}
