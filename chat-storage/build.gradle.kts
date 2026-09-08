import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.operit.storage.chat"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

// Keep the existing schema history and migration tests at their established app paths.
kapt {
    arguments { arg("room.schemaLocation", rootProject.file("app/schemas").path) }
}

dependencies {
    api(libs.room.runtime)
    api(libs.room.ktx)
    implementation(libs.kotlinx.serialization)
    implementation(project(":chat-parser"))
    kapt(libs.room.compiler)
}
