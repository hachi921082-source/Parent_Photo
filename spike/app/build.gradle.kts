import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 秘密情報はビルド時にプロジェクトルートのspike.local.propertiesから読む(出力・ログへは出さない)
val secrets = Properties().apply {
    val f = rootProject.file("../spike.local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String =
    (secrets.getProperty(key) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.parentphoto.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.parentphoto.spike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "spike"
        buildConfigField("String", "SMTP_USER", "\"${secret("smtp.user")}\"")
        buildConfigField("String", "SMTP_PASSWORD", "\"${secret("smtp.password")}\"")
        buildConfigField("String", "SMTP_TO", "\"${secret("smtp.to")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 重複リソースエラー対応: LICENSE.md が android-mail と android-activation で衝突した
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }
}

dependencies {
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
