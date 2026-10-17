package com.fintech.wallet;

import com.fintech.wallet.dto.AccountDto;
import com.fintech.wallet.dto.CreateAccountRequest;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:idempotency_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "fintech.outbox.publisher.enabled=false"
})
public class IdempotencyTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Test
    @DisplayName("Retrying a transfer with identical Idempotency-Key returns cached response without double-charging")
    void testIdempotencyPreventsDoubleCharging() {
        AccountDto sender = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-charlie")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("200.00"))
                .build());

        AccountDto receiver = walletService.createAccount(CreateAccountRequest.builder()
                .userId("user-dave")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("50.00"))
                .build());

        String sharedIdempotencyKey = "IDEMP-KEY-" + UUID.randomUUID();
        String refId = "REF-" + UUID.randomUUID();

        TransferRequest request = TransferRequest.builder()
                .referenceId(refId)
                .sourceAccountNumber(sender.getAccountNumber())
                .destinationAccountNumber(receiver.getAccountNumber())
                .amount(new BigDecimal("75.00"))
                .currency("USD")
                .description("Initial attempt")
                .build();

        // First attempt
        TransferResponse response1 = transferService.executeTransfer(sharedIdempotencyKey, request);
        assertThat(response1).isNotNull();
        assertThat(response1.getIdempotencyKey()).isEqualTo(sharedIdempotencyKey);

        // Immediate retry of identical transfer (e.g. client timeout or network hiccup)
        TransferResponse response2 = transferService.executeTransfer(sharedIdempotencyKey, request);
        assertThat(response2).isNotNull();
        assertThat(response2.getTransactionId()).isEqualTo(response1.getTransactionId());
        assertThat(response2.getReferenceId()).isEqualTo(response1.getReferenceId());

        // Balance check: Sender MUST be charged only once ($200 - $75 = $125)
        AccountDto finalSender = walletService.getAccountByNumber(sender.getAccountNumber());
        AccountDto finalReceiver = walletService.getAccountByNumber(receiver.getAccountNumber());

        assertThat(finalSender.getBalance()).isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(finalReceiver.getBalance()).isEqualByComparingTo(new BigDecimal("125.00"));
    }
}
