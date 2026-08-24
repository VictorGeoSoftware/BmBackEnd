# Multi-stage build for Kotlin/Ktor backend
FROM gradle:8.5-jdk17 AS build

WORKDIR /app

# Optional corporate CA trust for HTTPS (Maven Central, Gradle plugin portal).
# No-op on networks without SSL inspection (production VPS: certs/ holds only
# .gitkeep). Imports into the JVM truststore because Gradle ignores the OS store.
COPY certs/ /usr/local/share/ca-certificates/extra/
RUN update-ca-certificates || true; \
    for c in /usr/local/share/ca-certificates/extra/*.crt; do \
      [ -e "$c" ] || continue; \
      keytool -importcert -noprompt -trustcacerts \
        -alias "corp-$(basename "$c")" -file "$c" \
        -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit || true; \
    done

# Copy gradle files first for better caching
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build the application
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Optional corporate CA trust (no-op on the VPS). Append corporate PEM certs to
# the system bundle BEFORE apk so the HTTPS package fetch validates behind a
# TLS-inspecting proxy.
COPY certs/ /tmp/corp-certs/
RUN cat /tmp/corp-certs/*.crt >> /etc/ssl/certs/ca-certificates.crt 2>/dev/null || true; \
    rm -rf /tmp/corp-certs

# Install curl for healthchecks
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -S bmapp && adduser -S bmapp -G bmapp

# Copy the built JAR from build stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# Copy application resources
COPY --from=build /app/src/main/resources ./resources

# Ensure app directory ownership
RUN chown bmapp:bmapp /app/app.jar

# Switch to non-root user
USER bmapp

# Expose the application port
EXPOSE 8081

# Liveness only. Docker restarts the container when this fails, so it must not
# depend on Postgres: a database blip would otherwise restart a perfectly
# healthy backend, and a restart cannot fix someone else's database.
# Readiness ("can we serve traffic?") lives at /health/ready.
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8081/health/live || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
