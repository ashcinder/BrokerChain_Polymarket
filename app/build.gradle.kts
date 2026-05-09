plugins {
    alias(libs.plugins.android.application)
    // 如果你有使用 Kotlin，请保留。如果是全 Java 项目，可不加。
    // id("org.jetbrains.kotlin.android") version "1.8.10" apply false
}

android {
    namespace = "com.example.testing"
    compileSdk = 36 // 提示：API 36 非常新，请确保你的 Android Studio 已更新至最新预览版

    defaultConfig {
        applicationId = "com.example.testing"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "META-INF/DEPENDENCIES"
                excludes += "META-INF/LICENSE"
                excludes += "META-INF/LICENSE.txt"
                excludes += "META-INF/license.txt"
                excludes += "META-INF/NOTICE"
                excludes += "META-INF/NOTICE.txt"
                excludes += "META-INF/notice.txt"
                excludes += "META-INF/ASL2.0"
                excludes += "META-INF/io.netty.versions.properties"
                excludes += "META-INF/INDEX.LIST"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // 核心修复：确保 Java 8+ 特性（如 Lambda）在旧版本安卓上也能运行
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }


    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // 核心脱糖库：支持 Java 8+ 的时间、Lambda 等 API
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    implementation(libs.appcompat)
    // 如果 libs.material 报错，建议直接强行指定版本
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Web3j
    implementation("org.web3j:core:4.8.7-android") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }

    // 新版本 BouncyCastle
    implementation("org.bouncycastle:bcprov-jdk15to18:1.72")
}