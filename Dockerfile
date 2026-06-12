# Multi-stage Dockerfile for a JVM image of the BootUI sample app.
#
# BootUI is a multi-module Maven project, so the whole reactor is copied into
# the build stage and the sample app is built together with the modules it
# depends on (`-pl bootui-sample-app -am`).
#
# This image is deliberately kept small and low-CVE with three techniques:
#   1. The repackaged Spring Boot jar is exploded into its layers
#      (`-Djarmode=tools ... extract --layers`) and run via the JarLauncher, so
#      the large, rarely-changing dependency layer is cached/re-pushed less often
#      and there is no second copy of the fat jar baked into the image.
#   2. A custom, minimal Java runtime is assembled with `jlink` (only the JDK
#      modules the app needs, with debug symbols/man-pages/headers stripped),
#      replacing the full ~200 MB JRE.
#   3. The final stage is Google's "distroless" glibc base
#      (`gcr.io/distroless/base-debian12:nonroot`): it ships glibc and a CA bundle
#      but no shell, package manager, curl, perl or tar, so the runtime carries
#      almost no OS-package CVEs (the same base Dockerfile-native uses). It runs
#      as an unprivileged user (uid 65532) and brings the application in with
#      `COPY --chown` instead of a `RUN chown -R` layer that would otherwise
#      duplicate every copied file. Because it has no shell, the jlink runtime is
#      built on a glibc JDK (not the Alpine/musl one) and JVM flags are passed via
#      JAVA_TOOL_OPTIONS rather than a `sh -c` entrypoint (see below).
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
# Assemble a minimal custom Java runtime with jlink, in this same glibc JDK so
# the produced runtime's libc matches the distroless glibc runtime base below.
# ---------------------------------------------------------------------------
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

# An empty, non-root-owned state directory copied into the runtime image so BootUI
# can write its runtime state (config overrides under /app/.bootui) even though the
# distroless base has no shell to `mkdir`/`chown` at build time. uid:gid 65532 is
# the distroless "nonroot" user the final stage runs as.
RUN mkdir -p /runtime-state/.bootui && chown -R 65532:65532 /runtime-state

# ---------------------------------------------------------------------------
# Runtime stage: Google's "distroless" glibc base. It ships glibc and a CA bundle
# but no shell, package manager, curl, perl or tar - which removes the bulk of the
# OS-package CVEs an Alpine/Debian base otherwise carries. The jlink runtime above
# is glibc-linked to match it, and the :nonroot tag runs as an unprivileged user
# (uid 65532).
# ---------------------------------------------------------------------------
FROM gcr.io/distroless/base-debian12:nonroot

# Custom Java runtime assembled by jlink above.
ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=build /javaruntime $JAVA_HOME

WORKDIR /app

# Bring in the non-root-owned state directory first so /app and /app/.bootui are
# owned by the runtime user (uid 65532): BootUI writes config overrides there, and
# the distroless base has no shell to chown it afterwards. Heap dumps go to /tmp
# (see -XX:HeapDumpPath below), which is world-writable on the base.
COPY --from=build --chown=65532:65532 /runtime-state/ ./

# Copy the exploded application layers, ordered least- to most-frequently
# changing so Docker's layer cache is reused across rebuilds. COPY --chown sets
# ownership inline, avoiding a `RUN chown -R` layer (impossible here anyway: the
# distroless base has no shell) that would otherwise duplicate every copied file.
COPY --from=build --chown=65532:65532 /app/extracted/dependencies/ ./
COPY --from=build --chown=65532:65532 /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=65532:65532 /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=65532:65532 /app/extracted/application/ ./

USER nonroot

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

# JVM tuning flags for a small, predictable container footprint, passed via JAVA_TOOL_OPTIONS
# (the distroless base has no shell, so there is no `sh -c` to word-split a JAVA_OPTS variable; the
# JVM reads JAVA_TOOL_OPTIONS itself and handles the spaces). Heap, metaspace, code cache, direct
# memory and thread stacks are bounded explicitly (instead of -XX:MaxRAMPercentage); G1 with string
# deduplication and compact object headers (promoted to a product feature in JDK 25) keeps the
# footprint down, and the JVM fails fast with a heap dump on OutOfMemoryError. Override the whole set
# at runtime with -e JAVA_TOOL_OPTIONS="...".
ENV JAVA_TOOL_OPTIONS="-Xms200m -Xmx200m -XX:MaxMetaspaceSize=171m -XX:ReservedCodeCacheSize=240m -XX:MaxDirectMemorySize=10m -Xss512k -XX:+AlwaysPreTouch -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

EXPOSE 8080

# No Docker HEALTHCHECK: the distroless base has no shell, wget or curl to run a probe. Probe the web
# server (or /actuator/health) from your orchestrator's liveness/readiness checks instead.

# Launch the exploded application via the Spring Boot JarLauncher, in exec form so `java` is PID 1
# (and still receives SIGTERM from `docker stop` for Spring Boot's graceful shutdown). An absolute
# path is used because the distroless base has no shell to resolve $PATH. JVM flags come from
# JAVA_TOOL_OPTIONS above.
ENTRYPOINT ["/opt/java/bin/java", "org.springframework.boot.loader.launch.JarLauncher"]
