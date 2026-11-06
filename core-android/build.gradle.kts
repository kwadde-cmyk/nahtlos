plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.jenz.nahtlos.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        buildConfig = false
    }
    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core-model"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.hilt.android)
    implementation("androidx.hilt:hilt-lifecycle-viewmodel:1.3.0")
    ksp(libs.hilt.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.work.runtime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.security)
    implementation(libs.coil)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${libs.versions.junit.get()}")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${libs.versions.junit.get()}")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test> {
    useJUnitPlatform()
}
