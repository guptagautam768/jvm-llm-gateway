plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    implementation(project(":gateway-api"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
