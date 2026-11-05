plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun nahtlosProp(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val nahtlosPayments = !(nahtlosProp("NAHTLOS_PAYMENTS") ?: "true").equals("false", ignoreCase = true)
val nahtlosKeystore = nahtlosProp("NAHTLOS_KEYSTORE")?.let { file(it) }
val nahtlosStorePassword = nahtlosProp("NAHTLOS_KEYSTORE_PASSWORD")
val nahtlosKeyAlias = nahtlosProp("NAHTLOS_KEY_ALIAS")
val nahtlosKeyPassword = nahtlosProp("NAHTLOS_KEY_PASSWORD")
val nahtlosReleaseSigning = nahtlosKeystore != null && nahtlosKeystore.isFile &&
    !nahtlosStorePassword.isNullOrBlank() &&
    !nahtlosKeyAlias.isNullOrBlank() &&
    !nahtlosKeyPassword.isNullOrBlank()

android {
    namespace = "de.jenz.nahtlos"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.jenz.nahtlos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "PAYMENTS_ENABLED", nahtlosPayments.toString())
    }
    signingConfigs {
        if (nahtlosReleaseSigning) {
            create("release") {
                storeFile = nahtlosKeystore
                storePassword = nahtlosStorePassword
                keyAlias = nahtlosKeyAlias
                keyPassword = nahtlosKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("gradle/proguard-rules.pro"),
            )
            if (nahtlosReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (nahtlosReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    lint {
        // AGP-Lint stürzt auf diesem Detector mit der mitgelieferten Kotlin-Analyse ab.
        disable += "NullSafeMutableLiveData"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md}"
            excludes += "/META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(project(":core-android"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.reorderable)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.concurrent.futures)
    implementation(libs.guava)
}
