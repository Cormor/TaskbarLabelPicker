pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            content { includeGroupByRegex("androidx[.].*") }
        }
    }
}

rootProject.name = "taskbar-label-picker"
val bundledComponents = file("vendor/lixing-components/compose")
includeBuild(System.getenv("LIXING_TASKBAR_COMPONENTS") ?:
    if (bundledComponents.isDirectory) bundledComponents.path else "../LixingComponents/compose")
