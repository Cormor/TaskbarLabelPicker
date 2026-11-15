plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.lixing.components.gallery.MainKt"
        nativeDistributions { modules("jdk.accessibility") }
    }
}
