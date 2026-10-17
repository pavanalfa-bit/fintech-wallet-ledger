package com.fintech.wallet.exception;

public class LedgerInvariantException extends RuntimeException {
    public LedgerInvariantException(String message) {
        super(message);
    }
}
