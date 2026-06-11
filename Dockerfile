# Multi-stage Dockerfile for a JVM image of the BootUI sample app.
#
# BootUI is a multi-module Maven project, so the whole reactor is copied into
# the build stage and the sample app is built together with the modules it
# depends on (`-pl bootui-sample-app -am`).
#
# For a GraalVM native image, see Dockerfile-native.
# For a JVM image using CRaC, see Dockerfile-crac.
#
# The image runs with the "dev" Spring profile active by default (SPRING_PROFILES_ACTIVE=dev,
# baked in below), so it starts Docker-free on an in-memory H2 database with BootUI enabled.
#
# For a faster startup, the sample database migrations are disabled by default
# (SPRING_FLYWAY_ENABLED=false / SPRING_LIQUIBASE_ENABLED=false, baked in below).
#
# Build and run:
#   docker build -t bootui-sample-app .
#   docker run --rm -p 8080:8080 -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO bootui-sample-app
#   # then open http://localhost:8080/bootui
#
# Enable the sample database migrations (to populate the BootUI Flyway/Liquibase panels):
# The sample app can apply two pending Flyway migrations and two Liquibase change sets on startup
# so those panels have data to show. They are off by default for a faster boot; turn them back on
# at runtime with Spring Boot's environment variables:
#   docker run --rm -p 8080:8080 \
#     -e BOOTUI_TRUST_CONTAINER_GATEWAY=AUTO \
#     -e SPRING_FLYWAY_ENABLED=true -e SPRING_LIQUIBASE_ENABLED=true \
#     bootui-sample-app

# Build stage
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /app

# Copy the whole multi-module project (see .dockerignore for exclusions).
COPY . .
RUN chmod +x mvnw

# Build the sample app and its reactor dependencies.
RUN ./mvnw -DskipTests -pl bootui-sample-app -am clean package

# Runtime stage (Alpine-based JRE for a smaller image)
FROM eclipse-temurin:25-jre-alpine

# Install curl for healthchecks
RUN apk add --no-cache curl

# Create a non-root user
RUN adduser -D -u 1001 springboot

WORKDIR /app

# Copy the repackaged Spring Boot jar from the build stage
COPY --from=build /app/bootui-sample-app/target/bootui-sample-app-*.jar app.jar

RUN chown -R springboot:springboot /app

USER springboot

# Run with the "dev" profile *active* (not merely spring.profiles.default=dev): BootUI's activation
# checks Environment.getActiveProfiles(), which excludes spring.profiles.default, and the repackaged
# jar has devtools stripped. Without an active profile BootUI would stay disabled in the container.
# Override at runtime with -e SPRING_PROFILES_ACTIVE=... when you want a different profile.
ENV SPRING_PROFILES_ACTIVE=dev

# Disable the sample Flyway/Liquibase migrations by default for a faster startup. Re-enable them at
# runtime with -e SPRING_FLYWAY_ENABLED=true -e SPRING_LIQUIBASE_ENABLED=true to populate the
# BootUI Flyway/Liquibase panels.
ENV SPRING_FLYWAY_ENABLED=false
ENV SPRING_LIQUIBASE_ENABLED=false

EXPOSE 8080

# Health check using Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

# Run with container-aware JVM flags
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
