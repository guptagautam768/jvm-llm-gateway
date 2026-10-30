plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    api(project(":gateway-api"))
    api(libs.reactor.core)
    implementation(libs.slf4j.api)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.timelimiter)
    implementation(libs.resilience4j.reactor)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.reactor.test)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}
