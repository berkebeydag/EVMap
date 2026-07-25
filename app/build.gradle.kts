plugins {
    // No org.jetbrains.kotlin.android here: AGP 9 compiles Kotlin itself
    // (built-in Kotlin) and rejects the standalone plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Commits on HEAD, or 1 outside a git checkout so the build still works.
 *
 * Uses providers.exec rather than ProcessBuilder: the configuration cache refuses
 * external processes started directly at configuration time.
 */
fun gitCommitCount(): Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootDir
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.berke.ioniqscope"
    // Latest stable platform is android-37.1; recent AndroidX artifacts require
    // compiling against 37 or later.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.berke.ioniqscope"
        minSdk = 26
        targetSdk = 37
        // Derived from the commit count so it rises by itself and never goes
        // backwards. An update system is only as good as its version numbers, and
        // hand-edited ones get forgotten exactly when it matters.
        versionCode = gitCommitCount()
        versionName = "0.1.$versionCode"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Signed with the local debug key, on purpose. This is a personal app
            // that is sideloaded, never published, so there is no release keystore
            // to manage and no secret to keep out of the repository. It makes
            // `assembleRelease` produce something installable — which matters
            // because the debug APK is 20 MB of unminified dex and R8 takes it to
            // a quarter of that.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        // The updater compares the running build against the published one.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// kotlin.compilerOptions.jvmTarget is intentionally not set: with built-in Kotlin
// it defaults to android.compileOptions.targetCompatibility, declared above.

// Room schema export — keeps migrations reviewable in git.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid.android)
}
