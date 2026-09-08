plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
}
allprojects {
    group = "com.routiqo"
    version = "0.1.0"
    repositories { mavenCentral() }
}
subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
        "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
        "implementation"("org.springframework.boot:spring-boot-starter-security")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("com.tngtech.archunit:archunit-junit5:1.4.1")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test> { useJUnitPlatform() }
}

