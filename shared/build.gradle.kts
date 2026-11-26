import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

// Plain JVM module: used by the server, the desktop app and the Android app.
// Bytecode level 17 keeps it consumable from Android.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.okhttp)
}

tasks.test {
    useJUnitPlatform()
    // Contract test against a running Go server, e.g. ENTANGLEMENT_TEST_URL=http://127.0.0.1:18080
    environment("ENTANGLEMENT_TEST_URL", System.getenv("ENTANGLEMENT_TEST_URL").orEmpty())
    environment("ENTANGLEMENT_TEST_TOKEN", System.getenv("ENTANGLEMENT_TEST_TOKEN").orEmpty())
}
