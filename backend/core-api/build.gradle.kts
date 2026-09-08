plugins { java; id("org.springframework.boot") }
dependencies {
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}
tasks.processResources {
    from("../../packages/shared/src/catalog.json") { into("catalog") }
}
