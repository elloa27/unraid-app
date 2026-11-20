import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.unraid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unraid.app"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // GitHub Actions Secrets 기반 Release 서명 설정 (앞뒤 공백/개행 자동 제거, 미설정 시 Debug 빌드에 영향 없음)
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.trim()
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")?.trim()
    val keyAliasName = System.getenv("KEY_ALIAS")?.trim()
    val keyPasswordValue = System.getenv("KEY_PASSWORD")?.trim()
    val hasReleaseSigning = !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keyAliasName.isNullOrBlank() &&
        !keyPasswordValue.isNullOrBlank() &&
        File(keystorePath).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            // 1단계 MVP에서는 원격 디버깅 및 리플렉션/직렬화 안정성을 위해 난독화 비활성화
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX 기본 및 생명주기
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose + Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 네트워크, WebView, 로컬 저장소, 암호화 저장소, 직렬화
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // 2단계: gomobile로 빌드된 내장 Tailscale(tsnet) 안드로이드 브리지 라이브러리
    implementation(files("libs/tsnetbridge.aar"))
}
