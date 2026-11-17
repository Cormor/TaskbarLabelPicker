import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}

version = "0.1.8"

val bundledUpdate = file("vendor/lixing-update/compose/src/main/kotlin")
val updateClientSources = if (bundledUpdate.isDirectory) bundledUpdate
    else file("../LixingComponents/update/compose/src/main/kotlin")

kotlin {
    sourceSets.main { kotlin.srcDir(updateClientSources) }
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.lixing:lixing-components-compose:0.1.6")
}

compose.desktop {
    application {
        mainClass = "TaskbarPickerKt"
        nativeDistributions {
            packageName = "TaskbarLabelPicker"
            modules("jdk.accessibility")
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("resources/windows-x64/taskbar-label-picker.ico"))
            }
        }
    }
}

tasks.register<Copy>("prepareBridge") {
    from("native/TaskbarBridge.exe")
    into("resources/windows-x64")
}

tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach {
    dependsOn("prepareBridge")
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    inputs.file("native/TaskbarBridge.exe")
}
