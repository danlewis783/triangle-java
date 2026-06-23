plugins {
    java
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
    testImplementation(libs.assertj)
    // The bench source set sees the same dependencies as main (e.g. JNA).
    "benchImplementation"(sourceSets.main.get().output)
    "benchImplementation"(libs.jna)
    "benchImplementation"(libs.jackson.databind)
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
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}
