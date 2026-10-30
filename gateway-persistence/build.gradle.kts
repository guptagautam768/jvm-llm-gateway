plugins {
    id("jvm-llm-gateway.library-conventions")
}

dependencies {
    implementation(project(":gateway-tenancy"))
    implementation(libs.reactor.core)
    implementation(libs.slf4j.api)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.r2dbc.postgresql)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.r2dbc)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
