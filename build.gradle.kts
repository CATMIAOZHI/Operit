buildscript {
    val objectboxVersion by extra("5.3.0")
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:$objectboxVersion")
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

tasks.register("assembleDebugClone") {
    dependsOn(":app:assembleClone")
    description = "Build the clone (co-installable) debug APK with package name suffix .clone"
}

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

val buildBundledGithubExample =
    tasks.register<Exec>("buildBundledGithubExample") {
        group = "build"
        description = "Build the bundled GitHub package source before syncing APK assets"
        workingDir(rootDir)
        commandLine(if (isWindows) "npm.cmd" else "npm", "run", "build:examples:github")
    }

val syncBundledToolPkgPackages =
    tasks.register<Exec>("syncBundledToolPkgPackages") {
        group = "build"
        description = "Build and sync bundled ToolPkg packages into the Android assets directory"
        dependsOn(buildBundledGithubExample)
        workingDir(rootDir)
        commandLine(
            if (isWindows) "python" else "python3",
            "tools/example_packages/sync_example_packages.py",
            "--no-hot-reload"
        )
    }

tasks.register<GradleBuild>("assembleDebugWithToolPkg") {
    group = "build"
    description = "Sync bundled ToolPkg packages, then build the debug APK"
    dependsOn(syncBundledToolPkgPackages)
    dir = rootDir
    tasks = listOf(":app:assembleDebug")
}
