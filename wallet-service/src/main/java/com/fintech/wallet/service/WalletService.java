package com.fintech.wallet.service;

import com.fintech.wallet.dto.AccountDto;
import com.fintech.wallet.dto.CreateAccountRequest;
import com.fintech.wallet.dto.DepositRequest;
import com.fintech.wallet.exception.AccountNotFoundException;
import com.fintech.wallet.model.*;
import com.fintech.wallet.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final SecureRandom random = new SecureRandom();

    private static final String SYSTEM_CLEARING_USER = "SYSTEM_RESERVE";

    @Transactional
    public AccountDto createAccount(CreateAccountRequest request) {
        String accountNumber = generateAccountNumber(request.getAccountType());

        Account account = Account.builder()
                .accountNumber(accountNumber)
                .userId(request.getUserId())
                .accountType(request.getAccountType())
                .currency(request.getCurrency().toUpperCase())
                .balance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .build();

        Account saved = accountRepository.save(account);

        // If an initial balance was specified, fund it via double-entry from platform clearing
        if (request.getInitialBalance() != null && request.getInitialBalance().compareTo(BigDecimal.ZERO) > 0) {
            fundAccountFromSystemClearing(
                    saved,
                    request.getInitialBalance(),
                    "INITIAL_FUNDING_" + saved.getAccountNumber()
            );
        }

        log.info("Created account [{}] for user [{}] with currency [{}]",
                saved.getAccountNumber(), saved.getUserId(), saved.getCurrency());

        return toDto(saved);
    }

    @Transactional
    public AccountDto deposit(DepositRequest request) {
        Account userAccount = accountRepository.findByAccountNumberForUpdate(request.getAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + request.getAccountNumber()));

        fundAccountFromSystemClearing(userAccount, request.getAmount(), request.getReferenceId());
        return toDto(userAccount);
    }

    private void fundAccountFromSystemClearing(Account userAccount, BigDecimal amount, String referenceId) {
        Account systemReserve = getOrCreateSystemReserveAccount(userAccount.getCurrency());

        // Standard double-entry:
        // Asset (Platform Reserve) -> DEBIT amount (asset increases)
        // Liability (Customer Wallet) -> CREDIT amount (liability increases)
        systemReserve.applyEntry(EntryType.DEBIT, amount);
        userAccount.applyEntry(EntryType.CREDIT, amount);

        accountRepository.save(systemReserve);
        accountRepository.save(userAccount);

        List<LedgerService.EntryDraft> entries = List.of(
                LedgerService.EntryDraft.builder()
                        .account(systemReserve)
                        .entryType(EntryType.DEBIT)
                        .amount(amount)
                        .currency(userAccount.getCurrency())
                        .build(),
                LedgerService.EntryDraft.builder()
                        .account(userAccount)
                        .entryType(EntryType.CREDIT)
                        .amount(amount)
                        .currency(userAccount.getCurrency())
                        .build()
        );

        ledgerService.recordTransaction(
                referenceId,
                referenceId,
                TransactionType.DEPOSIT,
                "Deposit funding for " + userAccount.getAccountNumber(),
                entries
        );
    }

    public Account getOrCreateSystemReserveAccount(String currency) {
        String sysAccountNum = "SYS_RESERVE_" + currency.toUpperCase();
        return accountRepository.findByAccountNumber(sysAccountNum)
                .orElseGet(() -> {
                    Account reserve = Account.builder()
                            .accountNumber(sysAccountNum)
                            .userId(SYSTEM_CLEARING_USER)
                            .accountType(AccountType.ASSET)
                            .currency(currency.toUpperCase())
                            .balance(BigDecimal.ZERO)
                            .status(AccountStatus.ACTIVE)
                            .build();
                    return accountRepository.save(reserve);
                });
    }

    @Transactional(readOnly = true)
    public AccountDto getAccountByNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
        return toDto(account);
    }

    @Transactional(readOnly = true)
    public List<AccountDto> getAccountsByUserId(String userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private String generateAccountNumber(AccountType type) {
        String prefix = type == AccountType.ASSET ? "AST" : "WAL";
        long num = 1000000000L + (Math.abs(random.nextLong()) % 9000000000L);
        return prefix + num;
    }

    private AccountDto toDto(Account account) {
        return AccountDto.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .userId(account.getUserId())
                .accountType(account.getAccountType())
                .currency(account.getCurrency())
                .balance(account.getBalance())
                .status(account.getStatus())
                .version(account.getVersion())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }
}
