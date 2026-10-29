plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    api(project(":gateway-core"))
    implementation(libs.spring.webflux)
    implementation(libs.reactor.netty.http)
    implementation("org.springframework:spring-context")
    implementation(libs.slf4j.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.reactor.test)
    testImplementation(libs.wiremock)
    testImplementation(libs.blockhound.junit)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.platform.launcher)
}
