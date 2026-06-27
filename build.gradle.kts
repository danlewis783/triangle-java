import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone") version "4.0.1"
}

repositories {
    mavenCentral()
}

// Benchmark code lives in its own source set (src/bench/java): it is not part
// of the published jar (src/main) and is not run during `test` (src/test).
sourceSets {
    create("bench") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

dependencies {
    implementation(libs.jna)
    implementation(libs.jackson.databind)
    implementation(libs.jspecify)
    testImplementation(libs.assertj)
    // The bench source set sees the same dependencies as main (e.g. JNA).
    "benchImplementation"(sourceSets.main.get().output)
    "benchImplementation"(libs.jna)
    "benchImplementation"(libs.jackson.databind)
    "benchImplementation"(libs.jspecify)

    // Null-checking: NullAway runs as an Error Prone plugin (enabled on main only).
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.jupiter.get())
        }
    }
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the mesher micro-benchmark (Java vs native)."
    mainClass.set("com.acme.triangle.bench.MesherBenchmark")
    classpath = sourceSets["bench"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
}

java {
    // Compile with 17 (Error Prone/NullAway need a >=11 compiler to run) but keep
    // emitting Java 8 bytecode via release below, so the artifact stays Java 8.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.errorprone.isEnabled.set(false)   // off everywhere; enabled for main below
}

// NullAway only (not the rest of Error Prone), over the project's packages, main
// sources only - this is a nullness audit, not a general lint pass.
tasks.named<JavaCompile>("compileJava") {
    options.errorprone {
        isEnabled.set(true)
        disableAllChecks.set(true)
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "com.acme.triangle")
    }
}
