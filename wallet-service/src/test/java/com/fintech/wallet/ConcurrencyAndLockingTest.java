package com.fintech.wallet;

import com.fintech.wallet.dto.AccountDto;
import com.fintech.wallet.dto.CreateAccountRequest;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.model.AccountType;
import com.fintech.wallet.service.TransferService;
import com.fintech.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrency_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "fintech.outbox.publisher.enabled=false"
})
public class ConcurrencyAndLockingTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Test
    @DisplayName("Pessimistic concurrency control prevents account overdraw during simultaneous transfers")
    void testConcurrentTransfersCannotOverdrawAccount() throws InterruptedException {
        // Source account has $100.00
        AccountDto source = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-concurrency-source")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("100.00"))
                .build());

        AccountDto destination = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-concurrency-dest")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(BigDecimal.ZERO)
                .build());

        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("20.00"); // 10 * $20 = $200 attempted on a $100 balance

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    startLatch.await(); // ensure all threads fire at the exact same instant
                    String idempotencyKey = "CONCURRENCY-KEY-" + index + "-" + UUID.randomUUID();
                    String refId = "REF-CONC-" + index + "-" + UUID.randomUUID();

                    transferService.executeTransfer(idempotencyKey, TransferRequest.builder()
                            .referenceId(refId)
                            .sourceAccountNumber(source.getAccountNumber())
                            .destinationAccountNumber(destination.getAccountNumber())
                            .amount(transferAmount)
                            .currency("USD")
                            .description("Concurrent transfer #" + index)
                            .build());

                    successCount.incrementAndGet();
                } catch (InsufficientFundsException ex) {
                    failureCount.incrementAndGet();
                } catch (Exception ex) {
                    // unexpected error
                    ex.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completedInTime = endLatch.await(15, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(completedInTime).isTrue();
        // Exactly 5 transfers of $20 must succeed on $100 balance; the other 5 must fail with InsufficientFundsException
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failureCount.get()).isEqualTo(5);

        // Final balances must be exact
        AccountDto finalSource = walletService.getAccountByNumber(source.getAccountNumber());
        AccountDto finalDest = walletService.getAccountByNumber(destination.getAccountNumber());

        assertThat(finalSource.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(finalDest.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Deterministic lock ordering eliminates deadlocks between opposing transfers (A->B and B->A)")
    void testDeadlockFreedomInBidirectionalTransfers() throws InterruptedException {
        AccountDto accountA = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-deadlock-a")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("300.00"))
                .build());

        AccountDto accountB = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-deadlock-b")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("300.00"))
                .build());

        int threadCount = 20;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final boolean even = (i % 2 == 0);
            final int index = i;
            futures.add(executorService.submit(() -> {
                try {
                    startLatch.await();
                    String sourceAcc = even ? accountA.getAccountNumber() : accountB.getAccountNumber();
                    String destAcc = even ? accountB.getAccountNumber() : accountA.getAccountNumber();

                    transferService.executeTransfer(
                            "DEADLOCK-KEY-" + index + "-" + UUID.randomUUID(),
                            TransferRequest.builder()
                                    .referenceId("REF-DEADLOCK-" + index + "-" + UUID.randomUUID())
                                    .sourceAccountNumber(sourceAcc)
                                    .destinationAccountNumber(destAcc)
                                    .amount(new BigDecimal("10.00"))
                                    .currency("USD")
                                    .description("Bidirectional transfer #" + index)
                                    .build()
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    endLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        boolean finished = endLatch.await(20, TimeUnit.SECONDS);
        executorService.shutdown();

        assertThat(finished).isTrue();

        // 10 transfers A->B ($10 each = $100), 10 transfers B->A ($10 each = $100). Net change = $0!
        AccountDto finalA = walletService.getAccountByNumber(accountA.getAccountNumber());
        AccountDto finalB = walletService.getAccountByNumber(accountB.getAccountNumber());

        assertThat(finalA.getBalance()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(finalB.getBalance()).isEqualByComparingTo(new BigDecimal("300.00"));
    }
}
