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
    implementation(project(":fare-core"))

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
                // Pre-port CLI classes only; do not exempt future Inv*/Flight* classes.
                // Covered by CommandsTest plus ReviewRegressionTest, with an explicit
                // migration-to-80% tracking plan in docs/LEGACY-COVERAGE.md.
                classes(
                    "com.clawfare.cli.FlightAddCommand",
                    "com.clawfare.cli.FlightCommand",
                    "com.clawfare.cli.FlightCoverageCommand",
                    "com.clawfare.cli.FlightDedupeCommand",
                    "com.clawfare.cli.FlightDeleteCommand",
                    "com.clawfare.cli.FlightExportTasksCommand",
                    "com.clawfare.cli.FlightHistoryCommand",
                    "com.clawfare.cli.FlightImportCommand",
                    "com.clawfare.cli.FlightImportDbCommand",
                    "com.clawfare.cli.FlightImportResultsCommand",
                    "com.clawfare.cli.FlightListCommand",
                    "com.clawfare.cli.FlightMarkStaleCommand",
                    "com.clawfare.cli.FlightMatchCommand",
                    "com.clawfare.cli.FlightPriceCommand",
                    "com.clawfare.cli.FlightRefreshCommand",
                    "com.clawfare.cli.FlightShowCommand",
                    "com.clawfare.cli.FlightStaleCommand",
                    "com.clawfare.cli.FlightTagCommand",
                    "com.clawfare.cli.FlightTasksCommand",
                    "com.clawfare.cli.FlightUntagCommand",
                    "com.clawfare.cli.FlightUpdateCommand",
                    "com.clawfare.cli.FlightValidateCommand",
                    "com.clawfare.cli.InvCommand",
                    "com.clawfare.cli.InvConfigCommand",
                    "com.clawfare.cli.InvDeleteCommand",
                    "com.clawfare.cli.InvListCommand",
                    "com.clawfare.cli.InvNewCommand",
                    "com.clawfare.cli.InvResumeCommand",
                    "com.clawfare.cli.InvShowCommand",
                    "com.clawfare.cli.InvUrlsCommand",
                    "com.clawfare.cli.Output",
                    "com.clawfare.cli.PriceCheckFlight",
                    "com.clawfare.cli.PriceCheckResult",
                    "com.clawfare.cli.PriceCheckResults",
                    "com.clawfare.cli.PriceCheckTask",
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
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
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

// Build the React/TypeScript frontend before putting the HTML in the JVM
// classpath; do not distribute the former handwritten HTML template.
val installWebDependencies by tasks.registering(Exec::class) {
    workingDir = file("web")
    commandLine("npm", "install", "--no-audit", "--no-fund")
    inputs.file("web/package.json")
    outputs.dir("web/node_modules")
}
val buildWebUi by tasks.registering(Exec::class) {
    dependsOn(installWebDependencies)
    workingDir = file("web")
    commandLine("npm", "run", "build")
    inputs.dir("web/src")
    inputs.file("web/index.html")
    inputs.file("web/vite.config.ts")
    inputs.file("web/package.json")
    outputs.file("build/frontend/web/index.html")
}
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    dependsOn(buildWebUi)
    from("build/frontend")
}
