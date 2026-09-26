package com.routiqo.core.spot.infrastructure;

import com.routiqo.core.identity.application.AccountAgeReader;
import com.routiqo.core.identity.application.AccountWriteAuthority;
import com.routiqo.core.identity.application.AuthRateGate;
import com.routiqo.core.journey.application.ActiveJourneyReader;
import com.routiqo.core.journey.application.JourneyWriteAuthority;
import com.routiqo.core.moderation.application.ContributionRestrictionReader;
import com.routiqo.core.moderation.application.DurableBlockPolicyService;
import com.routiqo.core.security.ConditionalOnExactlyTrue;
import com.routiqo.core.spot.application.SpotContributionService;
import com.routiqo.core.spot.application.SpotBlockService;
import com.routiqo.core.spot.application.SpotContributionStore;
import com.routiqo.core.spot.application.SpotReportService;
import com.routiqo.core.spot.application.SpotReportStore;
import com.routiqo.core.spot.domain.AliasWords;
import com.routiqo.core.spot.domain.SpotCatalog;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spot contributions (ADR 0071): on only when both `ROUTIQO_SPOTS_API_ENABLED` and
 * `ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED` are exactly `true`, so reads can reach testers first.
 */
@Configuration(proxyBeanMethods = false)
@Profile("native-auth & routing & persistence")
@ConditionalOnExactlyTrue({"ROUTIQO_SPOTS_API_ENABLED", "ROUTIQO_SPOTS_CONTRIBUTIONS_ENABLED"})
public class SpotContributionsConfiguration {
    static final String ALIAS_WORDS = "/spot/alias-words-v1.txt";

    @Bean SpotContributionStore spotContributionStore(JdbcTemplate jdbc) {
        return new JdbcSpotContributionStore(jdbc);
    }

    @Bean SpotReportStore spotReportStore(JdbcTemplate jdbc) {
        return new JdbcSpotReportStore(jdbc);
    }

    @Bean SpotReportService spotReportService(AccountWriteAuthority accounts, SpotReportStore store) {
        return new SpotReportService(accounts, store, Clock.systemUTC());
    }

    @Bean SpotBlockService spotBlockService(AuthRateGate rates, SpotReportStore store,
            DurableBlockPolicyService blocks) {
        return new SpotBlockService(rates, store, blocks);
    }

    @Bean AliasWords spotAliasWords() {
        try (InputStream input = SpotContributionsConfiguration.class.getResourceAsStream(ALIAS_WORDS)) {
            if (input == null) throw new IllegalStateException("Alias word list is missing");
            return AliasWords.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("Alias word list is unreadable", unreadable);
        }
    }

    @Bean SpotContributionService spotContributionService(JourneyWriteAuthority journeys,
            AccountWriteAuthority accounts, ActiveJourneyReader activeJourneys,
            ContributionRestrictionReader restrictions, AccountAgeReader ages, SpotContributionStore store,
            SpotCatalog catalog, AliasWords aliases) {
        SecureRandom random = new SecureRandom();
        return new SpotContributionService(journeys, accounts, activeJourneys, restrictions, ages, store,
                catalog, aliases, Clock.systemUTC(), random::nextInt);
    }
}
