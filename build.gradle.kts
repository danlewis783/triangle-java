plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.assertj)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.jupiter.get())
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

val appMainClass = "com.acme.triangle.App"

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the CLI application."

    mainClass.set(appMainClass)
    classpath = sourceSets.main.get().runtimeClasspath

    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    )
}
