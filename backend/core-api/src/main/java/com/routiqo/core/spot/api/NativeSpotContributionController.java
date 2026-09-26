package com.routiqo.core.spot.api;

import com.routiqo.core.identity.application.AccountWriteUnavailable;
import com.routiqo.core.journey.application.JourneyNotFound;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.security.NativeAuthGuard;
import com.routiqo.core.spot.application.SpotActivityNeedsActiveJourney;
import com.routiqo.core.spot.application.SpotBlockService;
import com.routiqo.core.spot.application.SpotContributionConflict;
import com.routiqo.core.spot.application.SpotContributionForbidden;
import com.routiqo.core.spot.application.SpotContributionNotFound;
import com.routiqo.core.spot.application.SpotContributionService;
import com.routiqo.core.spot.application.SpotJourneyNotActive;
import com.routiqo.core.spot.application.SpotReportService;
import com.routiqo.core.spot.application.SpotsRateLimited;
import com.routiqo.core.spot.application.SpotsUnavailable;
import com.routiqo.core.spot.domain.ContributionLife;
import com.routiqo.core.spot.domain.PostText;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public one-tap signals, short posts, votes and "delete my post" (POSTS_AND_SIGNALS_SPEC, ADR 0071),
 * with Report and Block on those items (ADR 0072).
 */
@RestController
@RequestMapping("/api/v1/native/spots")
@Profile("native-auth & routing & persistence")
@ConditionalOnExactlyTrue({"ROUTIQO_SPOTS_API_ENABLED", "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED"})
public final class NativeSpotContributionController {
    private final SpotContributionService contributions;
    private final SpotReportService reports;
    private final SpotBlockService blocks;

    public NativeSpotContributionController(SpotContributionService contributions, SpotReportService reports,
            SpotBlockService blocks) {
        this.contributions = contributions;
        this.reports = reports;
        this.blocks = blocks;
    }

    @PostMapping("/signals") ResponseEntity<byte[]> signal(HttpServletRequest request) {
        UUID actor = actor(request);
        return json(NativeSpotContributionJson.receipt(
                contributions.submitSignal(actor, NativeSpotContributionJson.signal(request))));
    }

    @PostMapping("/posts") ResponseEntity<byte[]> post(HttpServletRequest request) {
        UUID actor = actor(request);
        return json(NativeSpotContributionJson.receipt(
                contributions.submitPost(actor, NativeSpotContributionJson.post(request))));
    }

    @PostMapping("/items/{ref}/vote") ResponseEntity<byte[]> vote(@PathVariable String ref,
            HttpServletRequest request) {
        UUID actor = actor(request);
        UUID item = NativeSpotContributionJson.ref(ref);
        return json(NativeSpotContributionJson.vote(
                contributions.vote(actor, item, NativeSpotContributionJson.vote(request))));
    }

    @PostMapping("/items/{ref}/delete") ResponseEntity<byte[]> delete(@PathVariable String ref,
            HttpServletRequest request) {
        UUID actor = actor(request);
        UUID item = NativeSpotContributionJson.ref(ref);
        NativeSpotContributionJson.empty(request);
        return json(NativeSpotContributionJson.receipt(contributions.deletePost(actor, item)));
    }

    /** Report a post or a signal summary (ADR 0072): 202 with a minimized receipt, nothing public. */
    @PostMapping("/items/{ref}/reports") ResponseEntity<byte[]> report(@PathVariable String ref,
            HttpServletRequest request) {
        UUID actor = actor(request);
        UUID item = NativeSpotContributionJson.ref(ref);
        byte[] body = NativeSpotContributionJson.reportReceipt(
                reports.report(actor, item, NativeSpotContributionJson.report(request)));
        return ResponseEntity.status(202).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * Block a post's author (ADR 0072): hides that alias in its room for the blocker and records the
     * account-level edge. 204 whether or not already blocked; the author is never named.
     */
    @PostMapping("/items/{ref}/block-author") ResponseEntity<Void> blockAuthor(@PathVariable String ref,
            HttpServletRequest request) {
        UUID actor = actor(request);
        UUID item = NativeSpotContributionJson.ref(ref);
        NativeSpotContributionJson.empty(request);
        blocks.blockAuthor(actor, item);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<byte[]> json(byte[] body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
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
    @ExceptionHandler(SpotContributionForbidden.class) ResponseEntity<Void> forbidden() {
        return ResponseEntity.status(403).build();
    }
    @ExceptionHandler({SpotContributionNotFound.class, JourneyNotFound.class}) ResponseEntity<Void> missing() {
        return ResponseEntity.status(404).build();
    }
    @ExceptionHandler({SpotContributionConflict.class, SpotJourneyNotActive.class,
            SpotActivityNeedsActiveJourney.class})
    ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    /** "Too old to post; it wasn't sent." */
    @ExceptionHandler(ContributionLife.ContributionTooOld.class) ResponseEntity<Void> tooOld() {
        return ResponseEntity.status(410).build();
    }
    /** "Links and phone numbers aren't allowed in posts." */
    @ExceptionHandler(PostText.ContactDetails.class) ResponseEntity<Void> contactDetails() {
        return ResponseEntity.status(422).build();
    }
    @ExceptionHandler(SpotsRateLimited.class) ResponseEntity<Void> limited(SpotsRateLimited limited) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds())).build();
    }
    @ExceptionHandler({SpotsUnavailable.class, AccountWriteUnavailable.class, DataAccessException.class,
            TransactionException.class, IllegalStateException.class})
    ResponseEntity<Void> unavailable() { return ResponseEntity.status(503).build(); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().build();
    }
}
