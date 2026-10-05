// Pure Kotlin/JVM: the bridge protocol (framing + messages) shared with the iOS implementation,
// unit-tested on any JVM. See docs/BLE_PROTOCOL.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
