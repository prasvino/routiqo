package com.routiqo.realtime;
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
   assertThat(health.body()).contains("\"UP\"","routiqo-realtime");
   assertThat(get("/api/v1/journeys").statusCode()).isIn(401,403);
   assertThat(get("/ws/route/anything").statusCode()).isIn(401,403);
 }
 
}

