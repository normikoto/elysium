plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.biribiri"
version = "0.0.1-SNAPSHOT"
description = "elysium"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // Spring Boot doesn't manage these versions. Both need their Spring Boot 4 artifacts, not the ones without "4" in the name.
    implementation("com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:17.0.0")
    implementation("info.picocli:picocli:4.7.7")
    // Images are re-encoded as WebP, which the JDK can't write. This runs the libwebp tools that come in its JAR, so
    // nothing has to be installed.
    implementation("com.sksamuel.scrimage:scrimage-core:4.6.9")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.6.9")
    // Reads the pages that profile fields link to, to check that they link back.
    implementation("org.jsoup:jsoup:1.23.2")
    // Unlike the JDK's client, this lets each client choose how hosts resolve, which is what keeps fetches from reaching
    // private addresses.
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("no.bekk.db-scheduler-ui:db-scheduler-ui-spring-boot-4-starter:5.0.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
    implementation("org.springframework.modulith:spring-modulith-observability-api")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // Spring Security's Argon2PasswordEncoder needs this. Spring Boot doesn't manage its version.
    runtimeOnly("org.bouncycastle:bcprov-jdk18on:1.84")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-session-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

springBoot {
    // NodeInfo reports the version from this. Leaving out the time keeps the task up to date between builds.
    buildInfo {
        excludes = setOf("time")
    }
}

tasks.bootJar {
    // The install steps in docs/installation.md use this name, so they don't need updating whenever the version changes.
    archiveFileName = "elysium.jar"
}

tasks.bootRun {
    systemProperty("spring.profiles.active", "dev")
    // Gradle doesn't forward stdin otherwise, and commands read passwords from it.
    standardInput = System.`in`
}

tasks.bootBuildImage {
    // compose.yaml runs this tag, so it doesn't need updating whenever the version changes.
    tags.add("elysium:latest")
    // The builder otherwise always mounts /var/run/docker.sock, which fails with rootless Docker. Binding the client's
    // socket path instead can fail with Docker Desktop or Colima, where that path isn't in the daemon's VM, so it can be
    // turned off with -PbindDockerHostToBuilder=false.
    docker {
        bindHostToBuilder = providers.gradleProperty("bindDockerHostToBuilder").map { it.toBoolean() }.orElse(true)
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("load")
    }
    // Gradle's 512 MB default runs out with many cached contexts and concurrent Argon2 hashing in FirstAccountIntegrationTests.
    maxHeapSize = "2g"
}

tasks.register<Test>("loadTest") {
    description = "Measures delivery under load, and prints the numbers. Takes minutes, and needs Docker."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("load")
    }
    testLogging.showStandardStreams = true
    // The numbers are the point, so a run that changes nothing still has to happen.
    outputs.upToDateWhen { false }
}
