package com.fintech.wallet;

import com.fintech.wallet.dto.AccountDto;
import com.fintech.wallet.dto.CreateAccountRequest;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.exception.LedgerInvariantException;
import com.fintech.wallet.model.*;
import com.fintech.wallet.repository.AccountRepository;
import com.fintech.wallet.repository.LedgerEntryRepository;
import com.fintech.wallet.repository.LedgerTransactionRepository;
import com.fintech.wallet.service.LedgerService;
import com.fintech.wallet.service.TransferService;
import com.fintech.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:double_entry_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "fintech.outbox.publisher.enabled=false"
})
public class DoubleEntryLedgerTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Debits strictly equal credits across every ledger transaction")
    void testDebitsEqualCreditsInvariant() {
        // Create sender and receiver accounts
        AccountDto sender = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-alice")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("500.00"))
                .build());

        AccountDto receiver = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-bob")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("100.00"))
                .build());

        String refId = "TX-DOUBLE-ENTRY-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        TransferResponse response = transferService.executeTransfer(idempotencyKey, TransferRequest.builder()
                .referenceId(refId)
                .sourceAccountNumber(sender.getAccountNumber())
                .destinationAccountNumber(receiver.getAccountNumber())
                .amount(new BigDecimal("175.50"))
                .currency("USD")
                .description("Test double-entry transfer")
                .build());

        assertThat(response).isNotNull();

        LedgerTransaction tx = transactionRepository.findByReferenceId(refId)
                .orElseThrow();

        List<LedgerEntry> entries = entryRepository.findByTransactionId(tx.getId());
        assertThat(entries).hasSize(2);

        BigDecimal sumDebits = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal sumCredits = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // MATHEMATICAL INVARIANT: SUM(DEBITS) == SUM(CREDITS)
        assertThat(sumDebits).isEqualByComparingTo(sumCredits);
        assertThat(sumDebits).isEqualByComparingTo(new BigDecimal("175.50"));

        // Verify updated account balances
        AccountDto updatedSender = walletService.getAccountByNumber(sender.getAccountNumber());
        AccountDto updatedReceiver = walletService.getAccountByNumber(receiver.getAccountNumber());

        assertThat(updatedSender.getBalance()).isEqualByComparingTo(new BigDecimal("324.50"));
        assertThat(updatedReceiver.getBalance()).isEqualByComparingTo(new BigDecimal("275.50"));
    }

    @Test
    @DisplayName("LedgerService strictly rejects unbalanced entries with LedgerInvariantException")
    void testUnbalancedLedgerEntriesRejected() {
        Account dummyAcc1 = accountRepository.save(Account.builder()
                .accountNumber("ACC-DUMMY-1")
                .userId("usr-1")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .balance(new BigDecimal("100.00"))
                .status(AccountStatus.ACTIVE)
                .build());

        Account dummyAcc2 = accountRepository.save(Account.builder()
                .accountNumber("ACC-DUMMY-2")
                .userId("usr-2")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .balance(new BigDecimal("100.00"))
                .status(AccountStatus.ACTIVE)
                .build());

        // Attempting an unbalanced entry: Debit $100 vs Credit $90
        List<LedgerService.EntryDraft> unbalancedEntries = List.of(
                LedgerService.EntryDraft.builder()
                        .account(dummyAcc1)
                        .entryType(EntryType.DEBIT)
                        .amount(new BigDecimal("100.00"))
                        .currency("USD")
                        .build(),
                LedgerService.EntryDraft.builder()
                        .account(dummyAcc2)
                        .entryType(EntryType.CREDIT)
                        .amount(new BigDecimal("90.00"))
                        .currency("USD")
                        .build()
        );

        assertThatThrownBy(() -> ledgerService.recordTransaction(
                "UNBALANCED-REF-" + UUID.randomUUID(),
                "KEY-UNBALANCED",
                TransactionType.TRANSFER,
                "Illegal unbalanced transaction",
                unbalancedEntries
        )).isInstanceOf(LedgerInvariantException.class)
          .hasMessageContaining("Ledger imbalance: Debits (100.00 USD) do not equal Credits (90.00 USD)");
    }
}
