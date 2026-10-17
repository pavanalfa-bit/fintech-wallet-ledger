package com.fintech.wallet.model;

public enum AccountType {
    ASSET,       // e.g., Platform bank clearing, cash reserves (debit normal)
    LIABILITY,   // e.g., Customer wallet balance owed to user (credit normal)
    EQUITY,      // e.g., Capital
    REVENUE,     // e.g., Platform fee income (credit normal)
    EXPENSE      // e.g., Processing fees incurred (debit normal)
}
