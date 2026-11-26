import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.swing)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":shared")))
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "app.entanglement.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Entanglement"
            packageVersion = "1.0.0"
            description = "Entangled Markdown files"
            vendor = "Entanglement"
            modules("java.naming", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/entanglement.ico"))
                menuGroup = "Entanglement"
                shortcut = true
                perUserInstall = true
                // Must never change: lets newer MSIs upgrade older installations.
                upgradeUuid = "7d3b6a52-3f0e-4c8e-9a51-2b1f0c6e4e21"
            }
        }
    }
}
