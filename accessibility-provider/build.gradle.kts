import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    namespace = "com.rainy.operitry.provider"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.rainy.operitry.provider"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        val path = local.getProperty("RELEASE_STORE_FILE")
        if (path != null && file(path).exists()) {
            create("personal") {
                storeFile = file(path)
                storePassword = local.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = local.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = local.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("personal")?.let { signingConfig = it } }
        release { signingConfigs.findByName("personal")?.let { signingConfig = it } }
    }
    buildFeatures { aidl = true }
    sourceSets["main"].aidl.srcDir("../app/src/main/aidl")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
