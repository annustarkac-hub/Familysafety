# Dependency Inventory

All Android versions are declared in [`android/gradle/libs.versions.toml`](../android/gradle/libs.versions.toml);
backend versions in [`backend/build.gradle.kts`](../backend/build.gradle.kts) (most managed by the Spring Boot BOM).
Every resolved artifact is pinned by SHA-256 in `gradle/verification-metadata.xml` (dependency verification).

## Android

| Library | Version | Licence | Purpose |
|---|---|---|---|
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | Build |
| Kotlin (+ Compose compiler, serialization plugins) | 2.0.21 | Apache-2.0 | Language |
| KSP | 2.0.21-1.0.28 | Apache-2.0 | Annotation processing (Hilt, Room) |
| Hilt (dagger) | 2.52 | Apache-2.0 | Dependency injection |
| androidx.hilt (navigation-compose, work) | 1.2.0 | Apache-2.0 | Hilt ViewModels / Workers |
| androidx.core:core-ktx | 1.15.0 | Apache-2.0 | |
| androidx.lifecycle (runtime, compose, viewmodel, process, service) | 2.8.7 | Apache-2.0 | Lifecycle, ProcessLifecycleOwner |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | |
| Compose BOM (ui, material3, icons-extended, tooling) | 2024.12.01 | Apache-2.0 | UI |
| androidx.navigation:navigation-compose | 2.8.5 | Apache-2.0 | Navigation |
| androidx.room (runtime, ktx, compiler) | 2.6.1 | Apache-2.0 | Local cache |
| androidx.work:work-runtime-ktx | 2.10.0 | Apache-2.0 | Background sync / polling |
| androidx.datastore:datastore-preferences | 1.1.1 | Apache-2.0 | Encrypted session storage |
| kotlinx-coroutines (core, android) | 1.9.0 | Apache-2.0 | Concurrency |
| kotlinx-serialization-json | 1.7.3 | Apache-2.0 | JSON |
| Retrofit (+ kotlinx-serialization converter) | 2.11.0 | Apache-2.0 | REST client |
| OkHttp (+ logging-interceptor) | 4.12.0 | Apache-2.0 | HTTP / WebSocket |
| io.getstream:stream-webrtc-android | 1.3.8 | BSD-3-Clause (libwebrtc) | WebRTC (screen capture, peer connection) |

Test-only: JUnit 4.13.2, MockK 1.13.13, Turbine 1.2.0, Robolectric 4.14.1, kotlinx-coroutines-test 1.9.0,
OkHttp MockWebServer 4.12.0, androidx.test core 1.6.1 / ext-junit 1.2.1 / espresso 3.6.1, Compose ui-test, Room testing,
Work testing.

## Backend

| Library | Version | Licence | Purpose |
|---|---|---|---|
| Spring Boot (web, validation, security, oauth2-resource-server, data-jpa, websocket, actuator) | 3.5.7 | Apache-2.0 | Framework |
| Spring Framework | 6.2.12 | Apache-2.0 | |
| Spring Security | 6.5.6 | Apache-2.0 | AuthN/AuthZ, Argon2 encoder |
| Nimbus JOSE + JWT | 9.37.4 | Apache-2.0 | JWT encode/decode |
| Bouncy Castle bcprov-jdk18on | 1.80 | MIT | Argon2id implementation |
| Hibernate ORM | 6.6.33 | LGPL-2.1 | JPA |
| Flyway (core, database-postgresql) | 11.7.2 | Apache-2.0 | Migrations |
| PostgreSQL JDBC | (Boot-managed) | BSD-2-Clause | Driver |
| Jackson (+ kotlin module) | 2.19.2 | Apache-2.0 | JSON |
| Tomcat embed | 10.1.48 | Apache-2.0 | Servlet container + WebSocket |
| Kotlin | 2.0.21 | Apache-2.0 | |

Test-only: spring-boot-starter-test (JUnit 5.12.2, AssertJ 3.27.6, Mockito), spring-security-test,
Testcontainers 1.21.4 (overridden from Boot's 1.21.3 for Docker Engine 29 API compatibility), kotlin-test-junit5.

## Infrastructure images
`postgres:16-alpine`, `eclipse-temurin:21-jre-alpine`, `caddy:2-alpine`, `coturn/coturn:4.6`.

## Updating
Lint reports newer versions (`GradleDependency` warnings). Upgrade deliberately, run `./scripts/test-all.sh`, and
regenerate verification metadata (BUILD_GUIDE.md). Watch for: AGP ↔ Gradle ↔ Kotlin ↔ KSP ↔ Hilt compatibility, and
Testcontainers ≥ 1.21.4 with Docker Engine ≥ 29.
