plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.muraky.pricewidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.muraky.pricewidget"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    // 매 빌드마다 같은 서명키를 써야 앱을 지우지 않고 업데이트할 수 있음
    signingConfigs {
        create("shared") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
