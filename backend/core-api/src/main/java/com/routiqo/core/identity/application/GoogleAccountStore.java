package com.routiqo.core.identity.application;

import java.util.UUID;

public interface GoogleAccountStore {
    /** Accept only the result of successful Google verification, never client-supplied identity claims. */
    UUID resolve(GoogleIdentityVerifier.Identity identity);
}
