# Multi-stage build for Kotlin/Ktor backend
FROM gradle:8.5-jdk17 AS build

WORKDIR /app

# ─── Optional corporate CA trust (no-op when certs/ holds only .gitkeep) ───
# Behind a TLS-inspecting proxy, Gradle/Maven downloads over HTTPS must trust
# the proxy's root CA. The JVM validates against its own truststore (cacerts)
# and ignores the system ca-certificates store, so import every provided CA
# directly into cacerts.
COPY certs/ /tmp/corp-certs/
RUN for c in /tmp/corp-certs/*.crt; do \
        [ -e "$c" ] || continue; \
        echo "Trusting corporate CA: $(basename "$c")"; \
        keytool -importcert -noprompt -trustcacerts \
            -alias "corp-$(basename "$c" .crt)" \
            -file "$c" -keystore "$JAVA_HOME/lib/security/cacerts" \
            -storepass changeit; \
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
# Use Temurin's multi-arch (glibc) JRE, not -alpine: the Alpine images are
# amd64-only and won't build on Apple Silicon (arm64).
FROM eclipse-temurin:17-jre

WORKDIR /app

# Install curl for healthchecks (ca-certificates pulled in as a dependency)
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ─── Optional corporate CA trust (no-op when certs/ holds only .gitkeep) ───
# Lets outbound HTTPS (e.g. Firebase/FCM) validate behind the proxy at runtime.
COPY certs/ /tmp/corp-certs/
RUN for c in /tmp/corp-certs/*.crt; do \
        [ -e "$c" ] || continue; \
        cp "$c" /usr/local/share/ca-certificates/; \
        keytool -importcert -noprompt -trustcacerts \
            -alias "corp-$(basename "$c" .crt)" \
            -file "$c" -keystore "$JAVA_HOME/lib/security/cacerts" \
            -storepass changeit; \
    done; update-ca-certificates || true

# Create non-root user for security
RUN groupadd -r bmapp && useradd -r -g bmapp -s /usr/sbin/nologin bmapp

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

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8081/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
