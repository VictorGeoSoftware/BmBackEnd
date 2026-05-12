plugins {
    kotlin("jvm") version "2.0.21"
    application
    kotlin("plugin.serialization") version "2.0.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.bm.backend"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    implementation("io.ktor:ktor-server-rate-limit:2.3.12")
    implementation("io.ktor:ktor-server-call-logging:2.3.12")
    
    // HTTP Client for external API calls
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    
    // Database - Exposed ORM
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.55.0")

    // Database drivers
    runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.3") // Only needed by SqliteToPostgresMigration tool; remove after cutover
    implementation("org.postgresql:postgresql:42.7.4")

    // Connection pool
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Schema migrations
    implementation("org.flywaydb:flyway-core:10.21.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.21.0")
    
    // Logging - Updated to latest stable
    implementation("ch.qos.logback:logback-classic:1.5.7")
    
    // Validation
    implementation("io.ktor:ktor-server-request-validation:2.3.12")
    
    // Configuration
    implementation("io.ktor:ktor-server-config-yaml:2.3.12")

    // Metrics — Micrometer + Prometheus
    implementation("io.ktor:ktor-server-metrics-micrometer:2.3.12")
    implementation("io.micrometer:micrometer-registry-prometheus:1.13.3")

    // Firebase Admin
    implementation("com.google.firebase:firebase-admin:9.4.3")

    // PDF generation
    implementation("com.github.librepdf:openpdf:1.3.40")
    
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    // Force docker-java 3.5.1 for Docker Desktop 29+ API v1.44+ compatibility
    testImplementation("com.github.docker-java:docker-java-api:3.5.1")
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.5.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.bm.backend.ApplicationKt")
}

tasks.shadowJar {
    archiveBaseName.set("bm-backend")
    archiveVersion.set("1.0")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

// ---------------------------------------------------------------------------
// launchAll - starts Docling servers, n8n, and the Ktor backend in one go.
//
// Usage:  ./gradlew launchAll            (from terminal)
//         or create an IntelliJ run config for the Gradle task "launchAll"
//
// Ctrl+C stops everything (shutdown hook kills child processes).
// ---------------------------------------------------------------------------
tasks.register("launchAll") {
    group = "application"
    description = "Launch Docling servers, n8n, and the Ktor backend for local development"
    dependsOn("classes")

    doLast {
        val n8nDir = file("${rootProject.projectDir}/../n8n")
        if (!n8nDir.exists()) {
            throw GradleException("n8n directory not found at ${n8nDir.absolutePath}")
        }

        val processes = mutableListOf<Process>()

        fun startProcess(
            name: String,
            directory: File,
            command: List<String>,
            env: Map<String, String> = emptyMap(),
        ): Process {
            val pb = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
            pb.environment().putAll(env)

            val process = pb.start()
            processes.add(process)

            Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> println("[$name] $line") }
                }
            }.apply {
                isDaemon = true
                start()
            }

            return process
        }

        // Kill any existing processes on the ports we need
        fun killPort(port: Int) {
            try {
                val result = ProcessBuilder("bash", "-c", "lsof -ti tcp:$port")
                    .redirectErrorStream(true)
                    .start()
                val pids = result.inputStream.bufferedReader().readText().trim()
                result.waitFor()
                if (pids.isNotBlank()) {
                    println("Killing existing process(es) on port $port: $pids")
                    ProcessBuilder("bash", "-c", "lsof -ti tcp:$port | xargs kill -9")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                    Thread.sleep(1_000)
                }
            } catch (e: Exception) {
                println("Warning: could not check/kill port $port: ${e.message}")
            }
        }

        listOf(5000, 5001, 5678, 8081).forEach { killPort(it) }

        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down all services...")
            processes.reversed().forEach { proc ->
                proc.descendants().forEach { it.destroyForcibly() }
                proc.destroyForcibly()
            }
            println("All services stopped.")
        })

        println("--- Starting Docling servers (ports 5000 and 5001) ---")
        val doclingProcess = startProcess(
            name = "docling",
            directory = n8nDir,
            command = listOf("bash", "${n8nDir.absolutePath}/launch_docling_server.sh"),
        )
        Thread.sleep(4_000)
        if (!doclingProcess.isAlive) {
            throw GradleException("Docling process exited early. Check [docling] logs above.")
        }

        println("--- Building n8n custom nodes ---")
        val buildNodes = ProcessBuilder("bash", "-lc", "source ~/.nvm/nvm.sh && nvm use 22 && npm run build")
            .directory(n8nDir)
            .redirectErrorStream(true)
            .start()
        buildNodes.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { println("[n8n-build] $it") }
        }
        val buildExit = buildNodes.waitFor()
        if (buildExit != 0) {
            throw GradleException("n8n custom nodes build failed (exit code $buildExit). Check [n8n-build] logs above.")
        }

        println("--- Starting n8n (port 5678) ---")
        val n8nProcess = startProcess(
            name = "n8n",
            directory = n8nDir,
            command = listOf(
                "bash",
                "-lc",
                "source ~/.nvm/nvm.sh && nvm use 22 && if command -v n8n >/dev/null 2>&1; then n8n start; else npx n8n start; fi",
            ),
            env = mapOf(
                "N8N_CUSTOM_EXTENSIONS" to n8nDir.absolutePath,
                "N8N_RESTRICT_ENVIRONMENT_VARIABLES" to "false",
                "DOCLING_CUSTOMER_API_URL" to "http://localhost:5000",
                "DOCLING_PRICE_TABLES_API_URL" to "http://localhost:5001",
            ),
        )
        Thread.sleep(4_000)
        if (!n8nProcess.isAlive) {
            throw GradleException("n8n process exited early. Check [n8n] logs above.")
        }

        println("--- Starting Ktor backend (port 8081) ---")
        println("=== Services are running. Press Ctrl+C to stop everything. ===")

        val backendMainClass = application.mainClass.get()
        val runtimeClasspath = sourceSets.getByName("main").runtimeClasspath.asPath
        val backendProcess = startProcess(
            name = "backend",
            directory = rootProject.projectDir,
            command = listOf("java", "-cp", runtimeClasspath, backendMainClass),
            env = mapOf(
                "DB_URL" to (System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/bm_backend?sslmode=disable"),
                "DB_USER" to (System.getenv("DB_USER") ?: ""),
                "DB_PASSWORD" to (System.getenv("DB_PASSWORD") ?: ""),
            ),
        )

        val exitCode = backendProcess.waitFor()
        if (exitCode != 0) {
            throw GradleException("Backend exited with code $exitCode. Check [backend] logs above.")
        }
    }
}
