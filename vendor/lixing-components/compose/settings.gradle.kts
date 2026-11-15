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

rootProject.name = "lixing-components-compose"
include(":gallery")
