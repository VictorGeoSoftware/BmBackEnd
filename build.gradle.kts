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
    
    // Database - Updated to latest stable versions
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.55.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3") // Updated for security fixes
    
    // Logging - Updated to latest stable
    implementation("ch.qos.logback:logback-classic:1.5.7")
    
    // Validation
    implementation("io.ktor:ktor-server-request-validation:2.3.12")
    
    // Configuration
    implementation("io.ktor:ktor-server-config-yaml:2.3.12")
    
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation("io.mockk:mockk:1.13.8")
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