package com.routiqo.core.publiclive.api;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ROUTIQO_GOOGLE_CLIENT_ID=test-client.apps.googleusercontent.com",
    "ROUTIQO_WEB_ORIGIN=http://localhost:3000",
    "ROUTIQO_AUTH_SECURE_COOKIES=false",
    "ROUTIQO_VALHALLA_ORIGIN=http://127.0.0.1:18002",
    "ROUTIQO_PHOTON_ORIGIN=http://127.0.0.1:12322",
    "ROUTIQO_ROUTING_REGION_WEST=78", "ROUTIQO_ROUTING_REGION_SOUTH=11",
    "ROUTIQO_ROUTING_REGION_EAST=81", "ROUTIQO_ROUTING_REGION_NORTH=14",
    "ROUTIQO_COMMUNITY_TRAFFIC_V3_ENABLED=yes",
    "ROUTIQO_COMMUNITY_TRAFFIC_V3_PUBLISHER_ENABLED=yes",
    "ROUTIQO_COMMUNITY_TRAFFIC_V3_MAINTENANCE_ENABLED=yes",
    "ROUTIQO_PUBLIC_SIGNAL_INTENT_API_ENABLED=yes",
    "ROUTIQO_PUBLIC_SIGNAL_INTENT_SHARE_ENABLED=yes",
    "ROUTIQO_V3_ADMIN_ENABLED=yes",
    "ROUTIQO_V3_GRANT_ADMIN_ENABLED=yes",
    "ROUTIQO_V3_GRANT_ADMIN_MAINTENANCE_ENABLED=yes"
})
@ActiveProfiles({"persistence", "google-auth", "web-auth", "routing"})
class CommunityLiveFlagWordHttpTest extends CommunityLiveFlagFailClosedHttpTest {}
