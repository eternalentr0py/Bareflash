// Fake adbd and fastboot devices shared by the protocol modules' tests. This is
// test infrastructure, not production code: nothing in the app depends on it.
// It uses only the public APIs of :adb and :fastboot, like any other client.
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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=8")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":adb"))
    api(project(":fastboot"))
}
