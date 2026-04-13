plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
