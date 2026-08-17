// Top-level build file
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    // Kotlin 2.0+ moved the Compose compiler out of the Kotlin distribution and into this
    // separate versioned plugin - required now that we're off composeOptions.kotlinCompilerExtensionVersion
    // (see app/build.gradle.kts), which only ever worked with the old bundled K1.9 compiler.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
