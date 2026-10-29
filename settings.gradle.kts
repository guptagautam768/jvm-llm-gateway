pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jvm-llm-gateway"

include(
    "gateway-api",
    "gateway-core",
    "gateway-providers",
    "gateway-cache",
    "gateway-tenancy",
    "gateway-persistence",
    "gateway-guardrails",
    "gateway-app",
)
