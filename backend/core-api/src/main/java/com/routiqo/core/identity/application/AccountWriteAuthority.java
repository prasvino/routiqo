package com.routiqo.core.identity.application;

import java.util.UUID;

/**
 * Trusted synchronous boundary for writes serialized by an enabled account row. The actor must come
 * from independent authentication. Callers must enter without an ambient transaction and keep callback
 * work on the same thread, datasource and transaction without providers or nested authority entry.
 */
public interface AccountWriteAuthority {
    <T> T withEnabledAccount(UUID actorId, Work<T> work);

    @FunctionalInterface
    interface Work<T> {
        T execute();
    }
}
