import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    jacoco
}

allprojects {
    group = "dev.gautamgupta"
    version = "0.1.0-SNAPSHOT"
}

val jacocoRootReport = tasks.register<JacocoReport>("jacocoRootReport") {
    group = "verification"
    description = "Aggregates JaCoCo coverage from every module."
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named("check") {
    dependsOn(jacocoRootReport)
}

gradle.projectsEvaluated {
    val mainSources = subprojects.map { project ->
        project.extensions.getByType(SourceSetContainer::class.java).named("main").get()
    }
    jacocoRootReport.configure {
        dependsOn(subprojects.map { it.tasks.named("jacocoTestReport") })
        additionalSourceDirs.from(mainSources.map { it.allSource.srcDirs })
        sourceDirectories.from(mainSources.map { it.allSource.srcDirs })
        classDirectories.from(mainSources.map { it.output })
        executionData.from(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
    }
}
