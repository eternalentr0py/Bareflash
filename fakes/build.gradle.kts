// Fake adbd and fastboot devices shared by the protocol modules' tests. This is
// test infrastructure, not production code: nothing in the app depends on it.
// It uses only the public APIs of :adb and :fastboot, like any other client.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":adb"))
    api(project(":fastboot"))
}
