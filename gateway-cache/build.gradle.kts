plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    implementation(project(":gateway-api"))
    implementation(libs.reactor.core)
    implementation(libs.slf4j.api)
    implementation("io.lettuce:lettuce-core")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.reactor.test)
    testImplementation(libs.logback.classic)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
