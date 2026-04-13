plugins {
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "io.acionyx.tunguska"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
