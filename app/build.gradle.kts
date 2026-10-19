import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// リリースAPK名に付ける日時(JST)。ビルドごとに上書きされないようにする
val buildStamp: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Tokyo")
}.format(Date())

android {
    namespace = "com.example.parentphoto"
    // AGP 8.7.3(スパイクで検証済み)が正式対応する最新の API 35 に合わせる
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.parentphoto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 配布用の署名は未定のため、当面はデバッグ鍵で署名する(スパイクと同じ)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // android-mail と android-activation の重複ファイル対策(ds 11.4、スパイクで検証済み)
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
            pickFirsts += setOf("META-INF/mailcap", "META-INF/mimetypes.default", "META-INF/mailcap.default")
        }
    }

    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "FamilyPhoto_$buildStamp.apk"
            }
        }
    }
}

// Room のスキーマJSONを app/schemas/ に出力してリポジトリに含める(マイグレーション検証用)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.coroutines.android)

    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    // JVM単体テストではandroid.jarのorg.jsonが空実装のため本物を使う
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}
