plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compose compiler as its own versioned plugin, not composeOptions.kotlinCompilerExtensionVersion
    // (removed below) - required by Kotlin 2.0+, which no longer bundles the Compose compiler.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.cricketgame"
    // Bumped from 34: SceneView/Filament's AARs (gltfio, filament-utils) target newer platform
    // APIs than 34 provides.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cricketgame"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-v1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// android.kotlinOptions{jvmTarget=...} is a hard error as of the Kotlin 2.4 Gradle plugin - moved
// to the top-level compilerOptions DSL it now requires.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Matches the compose-bom SceneView 4.30.0 itself declares (see its POM's dependencyManagement)
    // so there's one resolved Compose version across our code and SceneView's, not two competing BOMs.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 3D rendering (Filament, via SceneView's Compose-native wrapper) - replaces the Canvas-based
    // MatchVisuals pitch/figures. 3D-only artifact, not arsceneview - no AR/camera-passthrough here.
    implementation("io.github.sceneview:sceneview:4.30.0")

    // Google Play services / AdMob - wire up when ready to integrate ads
    // implementation("com.google.android.gms:play-services-ads:23.2.0")

    testImplementation("junit:junit:4.13.2")
}
