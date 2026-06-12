# Multi-stage Dockerfile for a JVM image of the BootUI sample app.
#
# BootUI is a multi-module Maven project, so the whole reactor is copied into
# the build stage and the sample app is built together with the modules it
# depends on (`-pl bootui-sample-app -am`).
#
# This image is deliberately kept small with three techniques:
#   1. The repackaged Spring Boot jar is exploded into its layers
#      (`-Djarmode=tools ... extract --layers`) and run via the JarLauncher, so
#      the large, rarely-changing dependency layer is cached/re-pushed less often
#      and there is no second copy of the fat jar baked into the image.
#   2. A custom, minimal Java runtime is assembled with `jlink` (only the JDK
#      modules the app needs, with debug symbols/man-pages/headers stripped),
#      replacing the full ~200 MB JRE.
#   3. The final stage is a bare Alpine base (no bundled JDK/JRE) and brings the
#      application in with `COPY --chown` instead of a `RUN chown -R` layer that
#      would otherwise duplicate every copied file.
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

# ---------------------------------------------------------------------------
# Build stage: compile the reactor and explode the Spring Boot jar into layers.
# A glibc JDK (Ubuntu "noble") is used so the frontend-maven-plugin's downloaded
# Node.js binaries (glibc-linked) run while building the bundled Vue UI.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /app

# Install curl so the Maven wrapper can download maven-wrapper.jar (this base image
# ships without curl/wget, and the wrapper jar is not committed to the repository).
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the whole multi-module project (see .dockerignore for exclusions).
COPY . .
RUN chmod +x mvnw

# Build the sample app and its reactor dependencies.
RUN ./mvnw -DskipTests -pl bootui-sample-app -am clean package

# Explode the repackaged Spring Boot jar into its layers (dependencies,
# spring-boot-loader, snapshot-dependencies, application). These are copied
# separately into the runtime stage so the large, rarely-changing dependency
# layer is reused from cache across rebuilds, and the app runs exploded via the
# JarLauncher rather than re-copying the whole fat jar.
RUN cp bootui-sample-app/target/bootui-sample-app-*.jar app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# JRE stage: assemble a minimal custom Java runtime with jlink. Built on the
# Alpine (musl) JDK so the produced runtime's libc matches the Alpine runtime
# base below.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-alpine AS jre-build

# Deliberate superset of the JDK modules the sample app and BootUI use - several
# are loaded reflectively, so the set cannot be derived reliably with jdeps:
#   - JDBC / JPA / Hibernate: java.sql, java.sql.rowset, java.naming, java.transaction.xa
#   - Spring bean introspection (java.beans): java.desktop
#   - TLS + BootUI OSV scanning over HTTPS: java.net.http, jdk.crypto.ec, jdk.crypto.cryptoki
#   - Security / SASL / JAAS: java.security.jgss, java.security.sasl, jdk.security.auth
#   - AspectJ load-time weaving / agents: java.instrument
#   - XML config (Liquibase, Spring): java.xml, java.xml.crypto
#   - BootUI diagnostics: heap dumps (jdk.management), JFR startup timeline (jdk.jfr),
#     JMX metrics (java.management[.rmi], jdk.management.agent), attach (jdk.attach)
#   - Misc runtime needs: java.logging, java.scripting, java.compiler, java.rmi,
#     java.prefs, jdk.unsupported, jdk.charsets, jdk.net, jdk.zipfs
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.base,java.logging,java.naming,java.management,java.management.rmi,java.instrument,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,java.desktop,java.security.jgss,java.security.sasl,java.scripting,java.compiler,java.net.http,java.rmi,java.prefs,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.management,jdk.management.agent,jdk.attach,jdk.zipfs,jdk.charsets,jdk.net,jdk.security.auth \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /javaruntime

# ---------------------------------------------------------------------------
# Runtime stage: a bare Alpine base (no bundled JDK/JRE) plus the custom jlink
# runtime. alpine:3.23 matches the Temurin Alpine images above so the jlink
# runtime's musl libc lines up.
# ---------------------------------------------------------------------------
FROM alpine:3.23

# Custom Java runtime assembled by jlink above.
ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre-build /javaruntime $JAVA_HOME

# Create a non-root user and an application directory it owns. Making /app itself
# springboot-owned (not just its contents) lets BootUI write its runtime state -
# heap dumps and config overrides under /app/.bootui - at runtime. The chown is
# non-recursive (an empty dir here), so it adds a negligible layer rather than
# duplicating the copied application below.
RUN adduser -D -u 1001 springboot && \
    mkdir -p /app && \
    chown springboot:springboot /app

WORKDIR /app

# Copy the exploded application layers, ordered least- to most-frequently
# changing so Docker's layer cache is reused across rebuilds. COPY --chown sets
# ownership inline, avoiding a `RUN chown -R` layer that would otherwise
# duplicate every copied file in a fresh layer.
COPY --from=build --chown=springboot:springboot /app/extracted/dependencies/ ./
COPY --from=build --chown=springboot:springboot /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=springboot:springboot /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=springboot:springboot /app/extracted/application/ ./

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

# JVM tuning flags for a small, predictable container footprint. Heap, metaspace, code cache, direct
# memory and thread stacks are bounded explicitly (instead of -XX:MaxRAMPercentage); G1 with string
# deduplication and compact object headers (promoted to a product feature in JDK 25) keeps the
# footprint down, and the JVM fails fast with a heap dump on OutOfMemoryError. Override the whole set
# at runtime with -e JAVA_OPTS="...".
ENV JAVA_OPTS="-Xms200m -Xmx200m -XX:MaxMetaspaceSize=171m -XX:ReservedCodeCacheSize=240m -XX:MaxDirectMemorySize=10m -Xss512k -XX:+AlwaysPreTouch -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

EXPOSE 8080

# Health check using Spring Boot Actuator. The bare Alpine base ships BusyBox wget (no curl), which
# exits non-zero on a non-2xx response - so a 503 from a DOWN actuator health endpoint fails the check.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null "http://localhost:${SERVER_PORT:-8080}/actuator/health" || exit 1

# Launch the exploded application via the Spring Boot JarLauncher. `sh -c ... exec java` keeps java as
# PID 1 (so it still receives SIGTERM from `docker stop` for Spring Boot's graceful shutdown) while
# letting the shell word-split $JAVA_OPTS.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
