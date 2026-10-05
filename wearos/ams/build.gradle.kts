// Pure Kotlin/JVM: the AMS wire protocol and session logic, with no Android dependency,
// so it can be unit-tested on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
