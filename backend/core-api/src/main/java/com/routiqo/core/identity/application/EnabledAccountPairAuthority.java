package com.routiqo.core.identity.application;

import java.util.UUID;

/**
 * Trusted synchronous boundary for two current enabled accounts in one ordered transaction.
 * The actor must come from independent authentication and the target from an authorized reference;
 * account existence alone grants no target permission. Enter without an ambient or nested authority
 * transaction. Callback work stays on the same thread, datasource and transaction without providers.
 */
public interface EnabledAccountPairAuthority {
    <T> T withEnabledPair(UUID firstId, UUID secondId, Work<T> work);

    @FunctionalInterface
    interface Work<T> { T execute(); }
}
