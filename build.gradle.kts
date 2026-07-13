import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

group = "com.sherlog"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.sherlog.MainKt"
        // Large indexes (10M+ lines) need heap headroom.
        jvmArgs += listOf("-Xmx3g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Sherlog"
            packageVersion = "1.0.0"
        }
    }
}
