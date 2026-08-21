plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shunfeng.ledger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shunfeng.ledger"
        minSdk = 23
        targetSdk = 34
        versionCode = 45
        versionName = "1.4.5"
        // Tesseract 只打包中文和英文训练数据，减小体积
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 从环境变量或 gradle 属性读取签名信息，避免在源码中硬编码密钥
            val storePass = providers.gradleProperty("SHUNFENG_STORE_PASSWORD")
                .orElse(providers.environmentVariable("SHUNFENG_STORE_PASSWORD"))
                .getOrElse("")
            val keyPass = providers.gradleProperty("SHUNFENG_KEY_PASSWORD")
                .orElse(providers.environmentVariable("SHUNFENG_KEY_PASSWORD"))
                .getOrElse("")
            storeFile = file("release.jks")
            storePassword = storePass
            keyAlias = "shunfeng"
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    // Tesseract OCR 原生引擎（chi_sim + eng 训练数据内置 assets），分发在 jitpack
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android-openmp:4.7.0")
}
