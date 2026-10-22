import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("net.ltgt.errorprone") version "5.1.1"
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
    implementation(libs.guava)
    implementation(libs.fastutil.core)
    testImplementation(libs.assertj)
    // The bench source set sees the same dependencies as main (e.g. JNA).
    "benchImplementation"(sourceSets.main.get().output)
    "benchImplementation"(libs.jna)
    "benchImplementation"(libs.jackson.databind)
    "benchImplementation"(libs.jspecify)
    "benchImplementation"(libs.guava)
    "benchImplementation"(libs.fastutil.core)
    // JMH: core annotations/runner on the bench classpath, generator as the
    // annotation processor (emits the BenchmarkList + generated harness classes).
    "benchImplementation"(libs.jmh.core)
    "benchAnnotationProcessor"(libs.jmh.generator.annprocess)

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

// Failure capture is on by default for consumers; the suite exercises failing
// paths constantly, so keep it quiet here. FailureCaptureTest re-enables it
// against a temp directory to test the capture itself.
tasks.withType<Test>().configureEach {
    systemProperty("triangle.captureFailures", "false")
    jvmArgs("--enable-native-access=ALL-UNNAMED")   // JNA's System.load, JDK 22+
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the mesher micro-benchmark (Java vs native)."
    mainClass.set("com.example.placeholder.triangle.bench.MesherBenchmark")
    classpath = sourceSets["bench"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
}

// Profiles the pure-Java mesher under Java Flight Recorder on the Java 8
// toolchain: loops one scenario (no native runs, no harness noise) and dumps
// build/jfr/<scenario>.jfr on exit. E.g.:
//   ./gradlew profile --args="ref-rings-q33 30"
// Inspect with a modern JDK's `jfr` tool (`jfr view hot-methods <file>`).
tasks.register<JavaExec>("profile") {
    group = "verification"
    description = "Runs the java-mesher profiling loop under JFR (Java 8)."
    mainClass.set("com.example.placeholder.triangle.bench.ProfileDriver")
    classpath = sourceSets["bench"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
    doFirst {
        val scenario = args?.firstOrNull() ?: "ref-rings-q33"
        mkdir(layout.buildDirectory.dir("jfr"))
        jvmArgs(
            "-XX:FlightRecorderOptions=stackdepth=128",
            "-XX:StartFlightRecording=settings=profile,dumponexit=true," +
                "filename=build/jfr/$scenario.jfr"
        )
    }
}

// Statistically rigorous benchmarks via JMH (proper warmup, forks, variance).
// The forked measurement JVM inherits this launcher, so we measure on Java 8 to
// match the shipped artifact and the `bench` task above; bump the version here to
// measure on a newer JVM instead. Pass JMH args through, e.g.:
//   ./gradlew jmh --args="qual -f 1 -prof gc"
//   ./gradlew jmh --args="-rf json -rff build/jmh.json"
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH mesher benchmarks (Java vs native, size sweep)."
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["bench"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
}

java {
    // Compile with 25 (Error Prone needs a >=21 compiler to run) but keep
    // emitting Java 8 bytecode via release below, so the artifact stays Java 8.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Error Prone's default checks run on every source set. NullAway is off here and
// enabled for main below.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")   // JDK 25 flags release 8 as obsolete
    options.errorprone {
        disable("NullAway")
        excludedPaths.set(".*[/\\\\]build[/\\\\]generated[/\\\\].*")   // JMH harness code
    }
}

// NullAway over the project's packages, main sources only - a nullness audit.
tasks.named<JavaCompile>("compileJava") {
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "com.example.placeholder.triangle")
    }
}
