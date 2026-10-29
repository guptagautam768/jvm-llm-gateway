plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    api(project(":gateway-api"))
    api(libs.reactor.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.reactor.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
