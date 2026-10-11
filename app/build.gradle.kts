plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dsh.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dsh.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    /**
     * release 签名：从环境变量或本机 gradle.properties 读取，**不入库**。
     * 未配置时 release 仍是未签名产物（可 assembleDebug 正常开发）。
     */
    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("DSH_STORE_FILE").orNull
                ?: System.getenv("DSH_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("DSH_STORE_PASSWORD").orNull
                    ?: System.getenv("DSH_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("DSH_KEY_ALIAS").orNull
                    ?: System.getenv("DSH_KEY_ALIAS")
                keyPassword = providers.gradleProperty("DSH_KEY_PASSWORD").orNull
                    ?: System.getenv("DSH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (providers.gradleProperty("DSH_STORE_FILE").orNull != null ||
                System.getenv("DSH_STORE_FILE") != null
            ) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    // 扫码连接：ZXing 内嵌（不依赖 Google Play 服务，国内 ROM 可用）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
