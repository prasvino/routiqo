package com.routiqo.core.spot.api;

import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.security.NativeAuthGuard;
import com.routiqo.core.spot.application.SpotActivityNeedsActiveJourney;
import com.routiqo.core.spot.application.SpotActivityService;
import com.routiqo.core.spot.application.SpotCatalogService;
import com.routiqo.core.spot.application.SpotsRateLimited;
import com.routiqo.core.spot.application.SpotsUnavailable;
import com.routiqo.core.spot.domain.SpotCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Native Spot catalog delivery and the Spots-ahead activity read (SPOTS_SPEC, ADR 0070). */
@RestController
@RequestMapping("/api/v1/native/spots")
@Profile("native-auth & routing & persistence")
@ConditionalOnExactlyTrue("ROUTIQO_SPOTS_API_ENABLED")
public final class NativeSpotController {
    private final SpotCatalogService catalogs;
    private final SpotActivityService activity;
    private final PublishedSpotCatalog published;

    public NativeSpotController(SpotCatalog catalog, SpotCatalogService catalogs,
            SpotActivityService activity) {
        this.catalogs = catalogs;
        this.activity = activity;
        this.published = new PublishedSpotCatalog(catalog);
    }

    @GetMapping("/catalog") ResponseEntity<byte[]> catalog(HttpServletRequest request) {
        catalogs.admitRead(actor(request));
        if (published.matches(request.getHeaders(HttpHeaders.IF_NONE_MATCH)))
            return ResponseEntity.status(304).eTag(published.etag()).build();
        return ResponseEntity.ok().eTag(published.etag()).contentType(MediaType.APPLICATION_JSON)
                .body(published.body());
    }

    @PostMapping("/activity") ResponseEntity<byte[]> activity(HttpServletRequest request) {
        UUID actor = actor(request);
        var spotIds = NativeSpotJson.activityRequest(request);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(NativeSpotJson.activityResponse(activity.read(actor, spotIds)));
    }

    private static UUID actor(HttpServletRequest request) {
        Object value = request.getAttribute(NativeAuthGuard.ACCOUNT_ATTRIBUTE);
        if (!(value instanceof String account)) throw new SecurityException("Native account missing");
        var expected = request.getHeaders("X-Routiqo-Account");
        if (!expected.hasMoreElements() || !account.equals(expected.nextElement()) || expected.hasMoreElements())
            throw new SecurityException("Account context changed");
        return UUID.fromString(account);
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<Void> unauthenticated() {
        return ResponseEntity.status(401).build();
    }
    @ExceptionHandler(SpotActivityNeedsActiveJourney.class) ResponseEntity<Void> noActiveJourney() {
        return ResponseEntity.status(409).build();
    }
    @ExceptionHandler(SpotsRateLimited.class) ResponseEntity<Void> limited() {
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, "60").build();
    }
    @ExceptionHandler({SpotsUnavailable.class, NativeSpotJson.ResponseTooLarge.class,
            DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }
}
