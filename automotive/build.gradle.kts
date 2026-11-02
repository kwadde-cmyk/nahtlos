plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.jenz.nahtlos.automotive"
    compileSdk = 35
    defaultConfig {
        applicationId = "de.jenz.nahtlos.automotive"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val defaultServer = (
            (project.findProperty("NAHTLOS_DEFAULT_SERVER") as String?)
                ?: System.getenv("NAHTLOS_DEFAULT_SERVER")
                ?: ""
            ).replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "NAHTLOS_DEFAULT_SERVER", "\"$defaultServer\"")
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
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md}"
        }
    }
}

dependencies {
    implementation(project(":core-android"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
}
