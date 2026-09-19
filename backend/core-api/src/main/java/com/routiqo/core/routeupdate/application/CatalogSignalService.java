package com.routiqo.core.routeupdate.application;

import com.routiqo.core.routeupdate.domain.QuickSignalReceipt;
import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import com.routiqo.core.routeupdate.domain.SignalCommandGrant;
import com.routiqo.core.routeupdate.domain.SignalIssuanceExpectation;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Internal catalog-authoritative facade; it is not a public signal transport. */
public final class CatalogSignalService {
    private final SignalStorageService storage;
    private final RouteAnchorCatalog catalog;

    public CatalogSignalService(SignalStorageService storage, RouteAnchorCatalog catalog) {
        this.storage = Objects.requireNonNull(storage);
        this.catalog = Objects.requireNonNull(catalog);
    }

    public SignalCommandGrant issue(UUID actorId, UUID journeyId, UUID anchorId) {
        return storage.issueFromCatalog(actorId, journeyId, anchorId, catalog);
    }

    /** Legacy issue uses current authority; this overload requires the exact displayed context. */
    public SignalCommandGrant issueExpectedContext(UUID actorId, UUID journeyId, UUID anchorId,
            SignalIssuanceExpectation expectation) {
        if (expectation == null) throw new SignalStorageDenied();
        return storage.issueFromCatalog(actorId, journeyId, anchorId, catalog, expectation);
    }

    public QuickSignalReceipt accept(UUID actorId, UUID commandId,
            SignalCommandPolicy.SubmissionFingerprint submission,
            Duration evidenceLifetime, Duration retention) {
        return storage.acceptFromCatalog(actorId, commandId, submission,
                evidenceLifetime, retention, catalog);
    }

    public QuickSignalReceipt withdraw(UUID actorId, UUID journeyId, UUID commandId) {
        return storage.withdraw(actorId, journeyId, commandId);
    }

    @Override public String toString() { return "CatalogSignalService[private]"; }
}
