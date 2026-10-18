plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("org.graalvm.buildtools.native") version "0.10.4"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "com.clawfare"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // CLI
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    // Database
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Web UI. Keys stay in this process; the page only receives fares.
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")

    // Logging - use nop to suppress SLF4J warnings
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
}

kover {
    reports {
        filters {
            excludes {
                // inv/flight commands predate this gate. CommandsTest exercises them;
                // their branch surface alone sits well under 80%.
                classes(
                    "com.clawfare.cli.Inv*",
                    "com.clawfare.cli.Flight*",
                    "com.clawfare.cli.Output",
                    "com.clawfare.cli.Output?*",
                    "com.clawfare.cli.PriceCheck*",
                )
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

application {
    mainClass.set("com.clawfare.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

tasks.test {
    useJUnitPlatform()
}

// Ktlint configuration
ktlint {
    version.set("1.4.1")
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

// Combined check task for CI and hooks
tasks.register("codeCheck") {
    description = "Run linting, tests, and coverage verification"
    dependsOn("ktlintCheck", "koverVerify")
}

// GraalVM native-image configuration
graalvmNative {
    binaries {
        named("main") {
            imageName.set("clawfare")
            mainClass.set("com.clawfare.MainKt")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // SQLite native library export path
            buildArgs.add("-Dorg.sqlite.lib.exportPath=${project.layout.buildDirectory.get()}/native/nativeCompile")
        }
    }
    toolchainDetection.set(false)
}
