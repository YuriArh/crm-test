import org.springframework.boot.gradle.plugin.SpringBootPlugin

// End-to-end tests: real HTTP against the running Docker stack (`make up`), run with `make e2e`.
// They are skipped in a normal `./gradlew build`, which has no running stack.

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("tools.jackson.core:jackson-databind")
    // CarrierDatabase: the carrier changes its own data, as in the manual checks of docs/specs/demo-seed.md §8.
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    onlyIf("e2e tests need a running stack; run them with -Pe2e (make e2e)") {
        providers.gradleProperty("e2e").isPresent
    }
    systemProperty("e2e.baseUrl", providers.environmentVariable("E2E_BASE_URL").getOrElse("http://localhost:8080"))
    systemProperty("e2e.carrierUrl", providers.environmentVariable("CARRIER_BASE_URL").getOrElse("http://localhost:8081"))
    systemProperty("e2e.carrierDbUrl",
        providers.environmentVariable("CARRIER_DB_URL").getOrElse("jdbc:postgresql://localhost:5432/carrier"))
    systemProperty("e2e.carrierDbUser", providers.environmentVariable("CARRIER_DB_USER").getOrElse("carrier"))
    systemProperty("e2e.carrierDbPassword", providers.environmentVariable("CARRIER_DB_PASSWORD").getOrElse("carrier"))
    outputs.upToDateWhen { false }
}
