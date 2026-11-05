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
    namespace = "de.jenz.nahtlos.automotive"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.jenz.nahtlos.automotive"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val defaultServer = (
            (project.findProperty("NAHTLOS_DEFAULT_SERVER") as String?)
                ?: System.getenv("NAHTLOS_DEFAULT_SERVER")
                ?: ""
            ).replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "NAHTLOS_DEFAULT_SERVER", "\"$defaultServer\"")
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
    implementation(libs.coil.compose)
    compileOnly(files(android.sdkDirectory.resolve("platforms/android-36/optional/android.car.jar")))
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${libs.versions.junit.get()}")
}

tasks.withType<org.gradle.api.tasks.testing.Test> {
    useJUnitPlatform()
}
