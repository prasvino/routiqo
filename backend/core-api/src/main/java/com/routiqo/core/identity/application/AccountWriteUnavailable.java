package com.routiqo.core.identity.application;

/** Redacted failure for account-authority database or transaction unavailability. */
public final class AccountWriteUnavailable extends RuntimeException {
    public AccountWriteUnavailable() {
        super("Account write authority is unavailable");
    }
}
