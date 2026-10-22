// Pure Kotlin/JVM module: no Android, no JNI, no network. Everything in here
// is unit-tested on the host JVM against fake devices.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Java 8 bytecode against the JDK 8 API (-Xjdk-release=8 below). These modules run inside
// the Android app, whose class library is not JDK 17's. Compiled against the JDK 17 API,
// calls such as ByteBuffer.position(int) or CharBuffer.flip() are emitted with JDK 9
// return types that older Android versions do not have: a NoSuchMethodError, which
// kills the app. Building against the JDK 8 API makes that impossible.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    // Public API surface must be declared on purpose.
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=8")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":adb"))
    api(project(":fastboot"))
    testImplementation(project(":fakes"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
