plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    api(libs.jackson.databind)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
