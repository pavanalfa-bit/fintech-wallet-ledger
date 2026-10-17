package com.fintech.wallet;

import com.fintech.events.TransferCompletedEvent;
import com.fintech.wallet.dto.AccountDto;
import com.fintech.wallet.dto.CreateAccountRequest;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.model.AccountType;
import com.fintech.wallet.model.OutboxEvent;
import com.fintech.wallet.model.OutboxStatus;
import com.fintech.wallet.repository.OutboxEventRepository;
import com.fintech.wallet.service.TransferService;
import com.fintech.wallet.service.WalletService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("testcontainers")
public class WalletTestcontainersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testcontainers_wallet")
            .withUsername("test_user")
            .withPassword("test_password");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("fintech.outbox.publisher.enabled", () -> "true");
        registry.add("fintech.outbox.publisher.interval-ms", () -> "500");
    }

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("End-to-End Testcontainers: Flyway migrations on real PostgreSQL, ledger commit, outbox streaming to real Kafka")
    void testEndToEndLedgerAndKafkaOutboxFlow() {
        // 1. Create real accounts in PostgreSQL
        AccountDto sender = walletService.createAccount(CreateAccountRequest.builder()
                .userId("testcontainer-sender")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("1000.00"))
                .build());

        AccountDto receiver = walletService.createAccount(CreateAccountRequest.builder()
                .userId("testcontainer-receiver")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .initialBalance(new BigDecimal("200.00"))
                .build());

        String idempotencyKey = "TC-KEY-" + UUID.randomUUID();
        String refId = "TC-REF-" + UUID.randomUUID();

        // 2. Perform transfer
        TransferResponse response = transferService.executeTransfer(idempotencyKey, TransferRequest.builder()
                .referenceId(refId)
                .sourceAccountNumber(sender.getAccountNumber())
                .destinationAccountNumber(receiver.getAccountNumber())
                .amount(new BigDecimal("350.00"))
                .currency("USD")
                .description("Testcontainers real PostgreSQL and Kafka transfer")
                .build());

        assertThat(response).isNotNull();

        // 3. Verify Outbox table has recorded the event
        List<OutboxEvent> outboxList = outboxEventRepository.findAll();
        assertThat(outboxList).isNotEmpty();
        OutboxEvent txEvent = outboxList.stream()
                .filter(e -> e.getAggregateId().equals(response.getTransactionId().toString()))
                .findFirst()
                .orElseThrow();

        assertThat(txEvent.getTopic()).isEqualTo("wallet.transfers");
        assertThat(txEvent.getEventType()).isEqualTo("TransferCompletedEvent");

        // 4. Verify Kafka consumption from real Kafka broker
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "testcontainers-verify-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList("wallet.transfers"));

            boolean messageFound = false;
            long timeoutDeadline = System.currentTimeMillis() + 15000;

            while (System.currentTimeMillis() < timeoutDeadline && !messageFound) {
                var records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value().contains(refId)) {
                        messageFound = true;
                        assertThat(record.value()).contains("TransferCompletedEvent");
                        assertThat(record.value()).contains("350.00");
                        break;
                    }
                }
            }

            assertThat(messageFound).isTrue();
        }
    }
}
