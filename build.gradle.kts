// AGP 9 bundles KGP itself (2.2.10 for AGP 9.3.1). Overriding it on the buildscript
// classpath is the documented way to compile against a newer Kotlin:
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
