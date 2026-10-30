plugins {
    // Imports the Spring Boot BOM. The Boot plugin only adds its own import once this
    // plugin is applied; it does not apply the plugin itself.
    id("jvm-llm-gateway.library-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":gateway-api"))
    implementation(project(":gateway-core"))
    implementation(project(":gateway-providers"))
    implementation(project(":gateway-cache"))
    implementation(project(":gateway-tenancy"))
    implementation(project(":gateway-persistence"))
    implementation(project(":gateway-guardrails"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.r2dbc.postgresql)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.r2dbc)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.wiremock)
    testImplementation(libs.resilience4j.circuitbreaker)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("jvm-llm-gateway.jar")
}
