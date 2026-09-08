package com.routiqo.core;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpBoundaryTest {
 @Value("${local.server.port}") int port;
 private HttpResponse<String> get(String path) throws Exception {
   return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void healthIsPublicAndProtectedPathsFailClosed() throws Exception {
   var health=get("/api/v1/health"); assertThat(health.statusCode()).isEqualTo(200);
   assertThat(health.body()).contains("\"UP\"","routiqo-core-api");
   assertThat(get("/api/v1/journeys").statusCode()).isIn(401,403);
   assertThat(get("/api/v1/auth/csrf").statusCode()).isIn(401,403);
   assertThat(get("/ws/route/anything").statusCode()).isIn(401,403);
 }
 @Test void catalogHasPublicDestinationDataWithoutPersonLocation() throws Exception {
   var result=get("/api/v1/destinations");
   assertThat(result.statusCode()).isEqualTo(200);
   assertThat(result.body()).contains("Pondicherry").doesNotContain("userId","latitude","longitude");
 }
}
