plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.payments"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Patch releases ahead of Spring Boot 4.1.1's BOM: Tomcat 11.0.24 and Jackson 3.1.5 have published CVEs.
extra["tomcat.version"] = "11.0.26"
extra["jackson-bom.version"] = "3.1.7"

val resilience4jVersion = "2.4.0"
val embeddedPostgresVersion = "2.2.2"
val embeddedPostgresBinariesVersion = "17.11.0"
val archunitVersion = "1.5.1"
val jsonSchemaValidatorVersion = "3.0.7"
val zxingVersion = "3.5.4"
val nimbusJoseVersion = "10.10"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")
    implementation("com.google.zxing:core:$zxingVersion")
    implementation("com.nimbusds:nimbus-jose-jwt:$nimbusJoseVersion")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:$embeddedPostgresBinariesVersion"))
    testImplementation("io.zonky.test:embedded-postgres:$embeddedPostgresVersion")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-darwin-amd64")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("com.networknt:json-schema-validator:$jsonSchemaValidatorVersion")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("user.timezone", "UTC")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootTestRun") {
    mainClass = "com.payments.gateway.LocalDevApplication"
}
