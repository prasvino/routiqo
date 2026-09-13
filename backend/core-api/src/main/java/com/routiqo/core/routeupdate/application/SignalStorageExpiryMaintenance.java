package com.routiqo.core.routeupdate.application;

public interface SignalStorageExpiryMaintenance {
    int purgeExpiredGrants(int limit);

    int purgeExpiredReceipts(int limit);
}
