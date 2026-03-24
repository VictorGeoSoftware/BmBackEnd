# Multi-stage build for Kotlin/Ktor backend
FROM gradle:8.5-jdk17 AS build

WORKDIR /app

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

# Install curl for healthchecks
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -S bmapp && adduser -S bmapp -G bmapp

# Copy the built JAR from build stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# Copy application resources
COPY --from=build /app/src/main/resources ./resources

# Create directory for database with restricted permissions
RUN mkdir -p /app/data && chown -R bmapp:bmapp /app/data && chmod 700 /app/data
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
