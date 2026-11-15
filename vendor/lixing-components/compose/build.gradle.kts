import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    `java-library`
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}

group = "com.lixing"
version = "0.1.6"

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(compose.foundation)
    api(compose.runtime)
    api(compose.ui)
    implementation("org.json:json:20250517")
}

tasks.processResources {
    from("../src/lixing_components") {
        include("definitions.json", "tokens.json")
        into("lixing-components")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
