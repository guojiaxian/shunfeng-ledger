pluginManagement {
    repositories {
        // Aliyun mirrors first (fast & reachable in CN network), then official fallbacks
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // Tesseract4Android 分发在 jitpack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ShunfengLedger"
include(":app")
