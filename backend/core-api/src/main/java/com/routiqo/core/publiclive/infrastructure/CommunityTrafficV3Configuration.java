package com.routiqo.core.publiclive.infrastructure;

import com.routiqo.core.routeupdate.domain.RouteAnchorCatalog;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("web-auth & routing & persistence")
@ConditionalOnProperty(name = {"ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED",
        "ROUTIQO_LIVE_ANCHOR_RESOLVER_ENABLED"}, havingValue = "true")
public class CommunityTrafficV3Configuration {
    @Bean JdbcCommunityTrafficV3 communityTrafficV3(JdbcTemplate jdbc,
            PlatformTransactionManager manager, RouteAnchorCatalog catalog) {
        return new JdbcCommunityTrafficV3(jdbc, manager, catalog, Clock.systemUTC());
    }

    @Bean JdbcCommunityTrafficPublisherV3 communityTrafficPublisherV3(DataSource dataSource,
            RouteAnchorCatalog catalog) {
        return new JdbcCommunityTrafficPublisherV3(dataSource, catalog);
    }
}
