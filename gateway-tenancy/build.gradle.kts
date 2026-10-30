plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    implementation(project(":gateway-api"))
    implementation(libs.reactor.core)
    implementation(libs.caffeine)
    implementation(libs.slf4j.api)
    implementation(libs.spring.boot.starter.data.redis.reactive)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.reactor.test)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}
