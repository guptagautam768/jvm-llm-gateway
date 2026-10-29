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

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.wiremock)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("jvm-llm-gateway.jar")
}
