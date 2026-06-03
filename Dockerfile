# Multi-stage Dockerfile for a JVM image of the BootUI sample app.
#
# BootUI is a multi-module Maven project, so the whole reactor is copied into
# the build stage and the sample app is built together with the modules it
# depends on (`-pl bootui-sample-app -am`). For a native image, see
# Dockerfile-native.

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

EXPOSE 8080

# Health check using Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1

# Run with container-aware JVM flags
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
