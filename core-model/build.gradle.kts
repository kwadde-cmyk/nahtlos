import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(libs.bouncycastle)
    implementation(libs.serialization.json)
    compileOnly(libs.xmlpull)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${libs.versions.junit.get()}")
}

tasks.test {
    useJUnitPlatform()
}
