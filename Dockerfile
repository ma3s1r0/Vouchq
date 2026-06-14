# Backend (Spring Boot) image. Multi-stage OCI build — no local JDK/Gradle
# needed, only a container runtime. Standard `Dockerfile` name so both
# `docker build` and `podman build` pick it up with no flags.
# Stage 1: build the Spring Boot jar with Gradle.
FROM docker.io/library/gradle:8.12-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
# Tests run separately (the :app tests need a container runtime via Testcontainers).
RUN gradle :app:bootJar --no-daemon -x test

# Stage 2: minimal JRE runtime.
FROM docker.io/library/eclipse-temurin:21-jre AS runtime
WORKDIR /app
# curl backs the compose healthcheck; run as a non-root user (rootless Podman).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 vouchq
COPY --from=build /home/gradle/src/app/build/libs/*.jar app.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
