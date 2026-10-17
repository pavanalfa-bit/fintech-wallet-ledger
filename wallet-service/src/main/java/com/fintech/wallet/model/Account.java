package com.fintech.wallet.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean hasSufficientBalance(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return this.balance.compareTo(amount) >= 0;
    }

    /**
     * Applies entry according to standard double-entry accounting:
     * - LIABILITY / EQUITY / REVENUE (Credit-normal): Credit adds, Debit subtracts.
     * - ASSET / EXPENSE (Debit-normal): Debit adds, Credit subtracts.
     */
    public void applyEntry(EntryType entryType, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Entry amount must be positive");
        }
        if (this.status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Cannot apply entry to non-active account: " + this.accountNumber);
        }

        boolean isCreditNormal = (accountType == AccountType.LIABILITY ||
                                  accountType == AccountType.EQUITY ||
                                  accountType == AccountType.REVENUE);

        if (isCreditNormal) {
            if (entryType == EntryType.CREDIT) {
                this.balance = this.balance.add(amount);
            } else {
                if (this.balance.compareTo(amount) < 0) {
                    throw new IllegalStateException("Insufficient funds in account " + this.accountNumber);
                }
                this.balance = this.balance.subtract(amount);
            }
        } else {
            // Debit-normal
            if (entryType == EntryType.DEBIT) {
                this.balance = this.balance.add(amount);
            } else {
                this.balance = this.balance.subtract(amount);
            }
        }
    }
}
