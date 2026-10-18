// Pure Kotlin/JVM module: no Android, no JNI, no network. Everything in here
// is unit-tested on the host JVM against fake devices.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Public API surface must be declared on purpose.
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":usbcore"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
