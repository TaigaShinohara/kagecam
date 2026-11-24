plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "app.kagecam"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.kagecam"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.8.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    // 実機なし検証の標準（2026-11-23〜）：Robolectric＋Roborazzi の
    // スクリーンショットテストを単体テストとして回すために必要。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // 実機なし検証：Robolectric（JVM上の画面検証）＋Roborazzi（スクリーンショット比較）
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.29.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.29.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// テスト実行時のメモリ上限と、Robolectric の android-all 取得先。
// このVMでは Java のTLS通信が遮断されるため、curlで先に落とした
// .robolectric-deps/ があればオフラインでそこから読む。
// 通常の開発環境ではそのフォルダが無いので指定は無効になり、
// Robolectric が Maven Central から自動取得する。
tasks.withType<Test>().configureEach {
    // このVMでは IPv6 ループバックが横取りされて、Gradle のテストワーカーが
    // 本体と通信できずに死ぬ（SuiteTestClassProcessor の NPE になる）。
    // テストJVMにも IPv4 強制を渡すのが必須（他プロジェクトで実証済みの手当て）。
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    maxHeapSize = "1536m"
    testLogging {
        events("passed", "failed", "skipped")
    }
    val robolectricDeps = rootProject.file(".robolectric-deps")
    if (robolectricDeps.isDirectory) {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", robolectricDeps.absolutePath)
    }
    // 棚の日付表示などを決定的にするためタイムゾーンを固定する
    systemProperty("user.timezone", "Asia/Tokyo")
    // Roborazzi の record/verify の切り替えはプラグインのタスクに任せる
    // （recordRoborazziDebug でゴールデン作り直し、verifyRoborazziDebug で照合）。
}
