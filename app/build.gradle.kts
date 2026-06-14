// Control plane: Spring Boot API server. Depends on the OSS :parser module.
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation(project(":parser"))
    // Rule-based risk scanner library (MA3-86). Run on each newly ingested
    // ToolVersion to produce a scan_result that the policy engine evaluates.
    implementation(project(":scanner"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Auth/RBAC: HTTP Basic + role rules (MA3-71). SSO/SAML is Phase 2.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Email notification channel (MA3-85). Default OFF (기획서 §7): no MailSender
    // is auto-configured unless spring.mail.* is set, and the channel bean is
    // @ConditionalOnProperty(vouchq.notify.email.enabled=true).
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // AOP: enables the tenant-isolation aspect that turns on Hibernate's
    // orgFilter per request (MA3-70).
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // OpenAPI/Swagger UI: auto-exposes /v3/api-docs + /swagger-ui.html (MA3-80).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // JGit: clone/fetch Git sources for ingestion (MA3-75). Apache-2.0 / EDL.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

// Spring Boot's bootJar is the deployable artifact; the plain jar is noise.
tasks.named<Jar>("jar") { enabled = false }
