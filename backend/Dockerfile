# Self-contained build for hosting platforms (Render, Railway, Fly.io, …): compiles the jar inside Docker.
# Local docker-compose uses infrastructure/docker/backend.Dockerfile with a prebuilt jar instead.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
# Dependency verification (gradle/verification-metadata.xml) is enforced during this build.
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/build/libs/family-safety-backend.jar app.jar
USER app
EXPOSE 8080
# Small free instances (512 MB): cap heap and favour fast start-up.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", "/app/app.jar"]
